package io.skoshchi;

import io.narayana.lra.Current;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.logging.LRALogger;
import io.quarkus.runtime.StartupEvent;
import io.skoshchi.yaml.LRAProxyConfig;
import io.skoshchi.yaml.LRAProxyRouteConfig;
import io.skoshchi.yaml.LRASettings;
import io.skoshchi.yaml.MethodType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.*;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.core.Context;

import jakarta.ws.rs.client.Invocation.Builder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.lra.annotation.*;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static io.narayana.lra.LRAConstants.*;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.*;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.MANDATORY;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.NESTED;

@Path("")
@ApplicationScoped
public class LRAProxy {
    private static final String CANCEL_ON_FAMILY_PROP = "CancelOnFamily";
    private static final String CANCEL_ON_PROP = "CancelOn";

    private static final Logger log = Logger.getLogger(LRAProxy.class.getName());

    @Context
    protected ResourceInfo resourceInfo;

    @ConfigProperty(name = "quarkus.lra.coordinator-url")
    String coordinatorUrl;

    NarayanaLRAClient narayanaLRAClient;

    @ConfigProperty(name = "proxy.config-path")
    String configPath;

    private LRAProxyConfig config;
    private Map<String, LRARoute> lraRouteMap;

    public void init(@Observes StartupEvent ev) {
        config = loadYamlConfig(configPath);
        if (!isYamlOK(config)) {
            throw new IllegalStateException("YAML configuration is invalid: " + configPath);
        }
        lraRouteMap = getLraRouteMap();

        narayanaLRAClient = new NarayanaLRAClient(URI.create(coordinatorUrl));
    }


    @GET
    @Path("{path:.*}")
    public Response proxyGet(@Context HttpHeaders httpHeaders,
                             @Context UriInfo info,
                             @PathParam("path") String path) {
        return handleRequest(httpHeaders, info, "GET", path);
    }

    @POST
    @Path("{path:.*}")
    public Response proxyPost(@Context HttpHeaders httpHeaders,
                              @Context UriInfo info,
                              @PathParam("path") String path) {
        return handleRequest(httpHeaders, info, "POST", path);
    }

    @PUT
    @Path("{path:.*}")
    public Response proxyPut(@Context HttpHeaders httpHeaders,
                             @Context UriInfo info,
                             @PathParam("path") String path) {
        System.out.println("--------------- PUT -----------------");
        httpHeaders.getRequestHeaders().forEach((s, strings) ->
                System.out.println(s + " : " + strings));
        return handleRequest(httpHeaders, info, "PUT", path);
    }

    @DELETE
    @Path("{path:.*}")
    public Response proxyDelete(@Context HttpHeaders httpHeaders,
                                @Context UriInfo info,
                                @PathParam("path") String path) {
        return handleRequest(httpHeaders, info, "DELETE", path);
    }

    @PATCH
    @Path("{path:.*}")
    public Response proxyPatch(@Context HttpHeaders httpHeaders,
                               @Context UriInfo info,
                               @PathParam("path") String path) {
        return handleRequest(httpHeaders, info, "PATCH", path);
    }

    public Response handleRequest(HttpHeaders httpHeaders, UriInfo info, String httpMethod, String path) {
        path = path.startsWith("/") ? path : "/" + path;

        log.info("[handleRequest] " + httpMethod +
                " Incoming path: " + path);

        LRARoute lraProxyRouteConfig = lraRouteMap.get(path);
        Method method = resourceInfo.getResourceMethod();
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>(httpHeaders.getRequestHeaders());
        MultivaluedMap<String, String> queryParameters = info.getQueryParameters();


        if (lraProxyRouteConfig == null) {
            // no-op
            return sendRequest(httpMethod, path, headers, queryParameters);
        }

        LRA.Type type = lraProxyRouteConfig.getSettings() != null ? lraProxyRouteConfig.getSettings().getType() : null;

        URI lraId = Current.peek();
        URI newLRA = null;
        Long timeout = lraProxyRouteConfig.getSettings() != null ? lraProxyRouteConfig.getSettings().getTimeLimit() : 0L;
        ChronoUnit timeUnit = lraProxyRouteConfig.getSettings() != null ? lraProxyRouteConfig.getSettings().getTimeUnit() : ChronoUnit.SECONDS;

        URI suspendedLRA = null;
        URI incomingLRA = null;
        URI recoveryUrl;
        boolean isLongRunning = !(lraProxyRouteConfig.getSettings() != null && lraProxyRouteConfig.getSettings().isEnd());
        boolean requiresActiveLRA = false;
        ArrayList<Progress> progress = null;
        Response.Status.Family[] cancelOnFamily = null;
        Response.Status[] cancelOn = null;

        if (lraProxyRouteConfig.getSettings() != null) {

            cancelOnFamily = Optional.ofNullable(lraProxyRouteConfig.getSettings().getCancelOnFamily())
                    .map(list -> list.toArray(new Response.Status.Family[0]))
                    .orElse(new Response.Status.Family[0]);

            cancelOn = Optional.ofNullable(lraProxyRouteConfig.getSettings().getCancelOn())
                    .map(list -> list.toArray(new Response.Status[0]))
                    .orElse(new Response.Status[0]);
        }


        MethodType methodType = lraRouteMap.get(path).getMethodType();
        boolean endAnnotation = lraRouteMap.containsKey(path) && methodType != null;

        URI suspendLRA = null;
        URI currentLRA = null;
        URI parentLRA = null;
        URI terminateLRA = null;

        Response response;

        if (headers.containsKey(LRA_HTTP_CONTEXT_HEADER)) {
            try {
                incomingLRA = new URI(Current.getLast(headers.get(LRA_HTTP_CONTEXT_HEADER)));
            } catch (URISyntaxException e) {
                String msg = String.format("header %s contains an invalid URL %s",
                        LRA_HTTP_CONTEXT_HEADER, Current.getLast(headers.get(LRA_HTTP_CONTEXT_HEADER)));
                return Response.status(Response.Status.PRECONDITION_FAILED.getStatusCode())
                        .entity(msg)
                        .build();
            }

            if (methodType != null && methodType.equals(MethodType.LEAVE)) {

                /*
                 // leave the LRA
                Map<String, String> terminateURIs = NarayanaLRAClient.getTerminationUris(
                        resourceInfo.getResourceClass(), createUriPrefix(containerRequestContext), timeout);
                String compensatorId = terminateURIs.get("Link");
                 */

                Map<String, String> terminateURIs = getTerminationUris(getBasePath(path), config.getProxy().getUrl(), timeout);
                String compensatorId = terminateURIs.get("Link");

                if (compensatorId == null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("Missing complete or compensate definitions for: " + path)
                            .build();
                }

                progress = new ArrayList<>();

                try {
                    narayanaLRAClient.leaveLRA(incomingLRA, compensatorId);
                    progress.add(new Progress(ProgressStep.Left, null)); // leave succeeded
                } catch (WebApplicationException e) {
                    progress.add(new Progress(ProgressStep.LeaveFailed, e.getMessage())); // leave may have failed
                    return Response.status(e.getResponse().getStatus())
                            .entity(e.getMessage())
                            .build();
                } catch (ProcessingException e) { // a remote coordinator was unavailable
                    progress.add(new Progress(ProgressStep.LeaveFailed, e.getMessage())); // leave may have failed
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
                            .entity(e.getMessage())
                            .build();
                }

                // let the participant know which lra he left by leaving the header intact
            }
        }

        boolean isNotTransactional = false;

        if (type == null) {
            if (!endAnnotation) {
                Current.clearContext(headers);
            }

            if (incomingLRA != null) {
                Current.push(incomingLRA);
                suspendLRA = incomingLRA;
                currentLRA = incomingLRA;
                Current.addActiveLRACache(incomingLRA);
            }

            isNotTransactional = true;
        }

        String compensatorLink = null;

        if (!isNotTransactional) {

            if (!headers.containsKey(LRA_HTTP_CONTEXT_HEADER)) {
                if (lraId != null) {
                    incomingLRA = lraId;
                }
            }

            if (!endAnnotation) {
                try {
                    if (Current.getFirstParent(incomingLRA) != null) {
                        headers.putSingle(LRA_HTTP_PARENT_CONTEXT_HEADER, Current.getFirstParent(incomingLRA));
                    }
                } catch (UnsupportedEncodingException e) {
                    return Response.status(Response.Status.PRECONDITION_FAILED.getStatusCode())
                            .entity(String.format("incoming LRA %s contains an invalid parent: %s", incomingLRA, e.getMessage()))
                            .build();
                }

                switch (type) {
                    case MANDATORY:
                        if (isTxInvalid(incomingLRA, true)) {
                            return Response.status(Response.Status.PRECONDITION_FAILED.getStatusCode())
                                    .entity(type.name() + " but no tx")
                                    .build();
                        }

                        lraId = incomingLRA;
                        requiresActiveLRA = true;

                        break;
                    case NEVER: // a txn must not be present
                        if (isTxInvalid(incomingLRA, false)) {
                            return Response.status(Response.Status.PRECONDITION_FAILED.getStatusCode())
                                    .entity(type.name() + " but no tx")
                                    .build();
                        }

                        lraId = null; // must not run with any context

                        break;
                    case NOT_SUPPORTED:
                        suspendedLRA = incomingLRA;
                        lraId = null; // must not run with any context

                        break;
                    case NESTED:
                        // FALLTHROUGH
                    case REQUIRED:
                        if (incomingLRA != null) {
                            if (type == NESTED) {
                                headers.putSingle(LRA_HTTP_PARENT_CONTEXT_HEADER, incomingLRA.toASCIIString());

                                // if there is an LRA present nest a new LRA under it
                                suspendedLRA = incomingLRA;

                                if (progress == null) {
                                    progress = new ArrayList<>();
                                }

                                newLRA = lraId = narayanaLRAClient.startLRA(incomingLRA, method.getDeclaringClass().getName() + "#" + method.getName(), timeout, timeUnit);

                                if (newLRA == null) {
                                    // startLRA will have called abortWith on the request context
                                    // the failure plus any previous actions (the leave request) will be reported via the response filter
                                    return sendRequest(httpMethod, path, headers, queryParameters);
                                }
                            } else {
                                lraId = incomingLRA;
                                // incomingLRA will be resumed
                                requiresActiveLRA = true;
                            }

                        } else {
                            progress = new ArrayList<>();
                            newLRA = lraId = narayanaLRAClient.startLRA(null, method.getDeclaringClass().getName() + "#" + method.getName(), timeout, timeUnit);


                            if (newLRA == null) {
                                // startLRA will have called abortWith on the request context
                                // the failure and any previous actions (the leave request) will be reported via the response filter
                                return Response.status(BAD_REQUEST).entity("New LRA was not created inside the coordinator").build();
                            }
                        }

                        break;
                    case REQUIRES_NEW:
                        suspendedLRA = incomingLRA;

                        if (progress == null) {
                            progress = new ArrayList<>();
                        }
                        newLRA = lraId = narayanaLRAClient.startLRA(null, method.getDeclaringClass().getName() + "#" + method.getName(), timeout, timeUnit);
                        if (newLRA == null) {
                            // startLRA will have called abortWith on the request context
                            // the failure and any previous actions (the leave request) will be reported via the response filter
                            return Response.status(BAD_REQUEST).entity("New LRA was not created inside the coordinator").build();
                        }

                        break;
                    case SUPPORTS:
                        lraId = incomingLRA;

                        break;
                    default:
                        lraId = incomingLRA;
                }

                if (lraId == null) {
                    // the method call needs to run without a transaction
                    Current.clearContext(headers);

                    return sendRequest(httpMethod, path, headers, queryParameters);
                }

                if (!isLongRunning) {
                    terminateLRA = lraId;
                }

                if (newLRA != null) {
                    if (suspendedLRA != null) {
                        suspendedLRA = incomingLRA;
                    }
                }
                log.info("[lraId]: " + lraId);
                Current.updateLRAContext(lraId, headers); // make the current LRA available to the called method


                try {
                    narayanaLRAClient.setCurrentLRA(lraId); // make the current LRA available to the called method
                } catch (Exception e) {
                    // should not happen since lraId has already been validated
                    // (perhaps we should not use the client API to set the context)
                    return Response.status(Response.Status.BAD_REQUEST.getStatusCode())
                            .entity(e.getMessage())
                            .build();
                }

                if (!endAnnotation) { // don't enlist for methods marked with Compensate, Complete or Leave
                    Map<String, String> terminateURIs = getTerminationUris(getBasePath(path), config.getProxy().getUrl(), timeout);

                    String timeLimitStr = terminateURIs.get(TIMELIMIT_PARAM_NAME);
                    long timeLimit = timeLimitStr == null ? 0L : Long.parseLong(timeLimitStr);

                    if (terminateURIs.containsKey("Link")) {
                        try {
//                            String basePath = getBasePath(path);
//
//                            Map<MethodType, URI> urisByType = new EnumMap<>(MethodType.class);
//
//                            for (Map.Entry<String, LRARoute> entry : lraRouteMap.entrySet()) {
//                                String candidatePath = entry.getKey();
//                                LRARoute route = entry.getValue();
//
//                                if (route.getMethodType() != null && candidatePath.contains(basePath)) {
//                                    URI uri = new URI(config.getProxy().getUrl() + (candidatePath.startsWith("/") ? candidatePath : "/" + candidatePath));
//                                    urisByType.put(route.getMethodType(), uri);
//                                }
//                            }

                            StringBuilder linkHeaderValue = new StringBuilder();
                            makeLink(linkHeaderValue, COMPENSATE, toURI(terminateURIs.get(COMPENSATE)));
                            makeLink(linkHeaderValue, COMPLETE, toURI(terminateURIs.get(COMPLETE)));
                            makeLink(linkHeaderValue, FORGET, toURI(terminateURIs.get(FORGET)));
                            makeLink(linkHeaderValue, LEAVE, toURI(terminateURIs.get(LEAVE)));
                            makeLink(linkHeaderValue, AFTER, toURI(terminateURIs.get(AFTER)));
                            makeLink(linkHeaderValue, STATUS, toURI(terminateURIs.get(STATUS)));

                            compensatorLink = linkHeaderValue.toString();
                            StringBuilder previousParticipantData = new StringBuilder();
                            log.info("[compensatorLink]: " + compensatorLink);

                            recoveryUrl = narayanaLRAClient.enlistCompensator(lraId, timeLimit, compensatorLink, previousParticipantData);

                            progress = updateProgress(progress, ProgressStep.Joined, null);
                            headers.putSingle(LRA_HTTP_RECOVERY_HEADER,
                                    Pattern.compile("^\"|\"$").matcher(recoveryUrl.toASCIIString()).replaceAll(""));

                        } catch (WebApplicationException e) {
                            String reason = e.getMessage();

                            progress = updateProgress(progress, ProgressStep.JoinFailed, reason);
                            return Response.status(e.getResponse().getStatus())
                                    .entity(String.format("%s: %s", e.getClass().getSimpleName(), reason))
                                    .build();

                        } catch (URISyntaxException e) {
                            progress = updateProgress(progress, ProgressStep.JoinFailed, e.getMessage()); // one or more of the participant end points was invalid
                            return Response.status(Response.Status.BAD_REQUEST.getStatusCode())
                                    .entity(String.format("%s %s: %s", lraId, e.getClass().getSimpleName(), e.getMessage()))
                                    .build();
                        } catch (ProcessingException e) {
                            progress = updateProgress(progress, ProgressStep.JoinFailed, e.getMessage()); // a remote coordinator was unavailable
                            return Response.status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
                                    .entity(String.format("%s %s,", e.getClass().getSimpleName(), e.getMessage()))
                                    .build();
                        }
                    } else if (requiresActiveLRA && narayanaLRAClient.getStatus(lraId) != LRAStatus.Active) {
                        Current.clearContext(headers);
                        Current.pop(lraId);
                        suspendedLRA = null;

                        if (type == MANDATORY) {
                            return Response.status(Response.Status.PRECONDITION_FAILED.getStatusCode())
                                    .entity("LRA should have been active: ")
                                    .build();
                        }
                    }
                }
                currentLRA = lraId;
                Current.addActiveLRACache(lraId);
            }
        }

        // ================= SEND THE REQUEST =================
        response = sendRequest(httpMethod, path, headers, queryParameters);

        // ================= RESPONSE FILTER =================
        boolean isCancel = isJaxRsCancel(response, cancelOnFamily, cancelOn);

        try {
            if (currentLRA != null && isCancel) {
                try {
                    // do not attempt to cancel if the request filter tried but failed to start a new LRA
                    if (progress == null || progressDoesNotContain(progress, ProgressStep.StartFailed)) {
                        narayanaLRAClient.cancelLRA(currentLRA);
                        progress = updateProgress(progress, ProgressStep.Ended, null);
                    }
                } catch (NotFoundException ignore) {
                    // must already be cancelled (if the intercepted method caused it to cancel)
                    // or completed (if the intercepted method caused it to complete)
                    progress = updateProgress(progress, ProgressStep.Ended, null);
                } catch (WebApplicationException e) {
                    progress = updateProgress(progress, ProgressStep.CancelFailed, e.getMessage());
                } catch (ProcessingException e) {
                    method = resourceInfo.getResourceMethod();
                    LRALogger.i18nLogger.warn_lraFilterContainerRequest("ProcessingException: " + e.getMessage(),
                            method.getDeclaringClass().getName() + "#" + method.getName(), currentLRA.toASCIIString());

                    progress = updateProgress(progress, ProgressStep.CancelFailed, e.getMessage());
                    terminateLRA = null;
                } finally {
                    if (currentLRA.toASCIIString().equals(
                            Current.getLast(headers.get(LRA_HTTP_CONTEXT_HEADER)))) {
                        // the callers context was ended so invalidate it
                        headers.remove(LRA_HTTP_CONTEXT_HEADER);
                    }

                    if (terminateLRA != null && terminateLRA.toASCIIString().equals(currentLRA.toASCIIString())) {
                        terminateLRA = null; // don't attempt to finish the LRA twice
                    }
                }
            }

            if (terminateLRA != null) {
                try {
                    // do not attempt to close or cancel if the request filter tried but failed to start a new LRA
                    if (progress == null || progressDoesNotContain(progress, ProgressStep.StartFailed)) {
                        if (isCancel) {
                            narayanaLRAClient.cancelLRA(terminateLRA, compensatorLink, "data");
                        } else {
                            narayanaLRAClient.closeLRA(terminateLRA, compensatorLink, "data");
                        }

                        progress = updateProgress(progress, ProgressStep.Ended, null);
                    }
                } catch (WebApplicationException e) {
                    if (e.getResponse().getStatus() == jakarta.ws.rs.core.Response.Status.NOT_FOUND.getStatusCode()) {
                        // must already be cancelled (if the intercepted method caused it to cancel)
                        // or completed (if the intercepted method caused it to complete
                        progress = updateProgress(progress, ProgressStep.Ended, null);
                    } else {
                        // same as ProcessingException case
                        progress = updateProgress(progress,
                                isCancel ? ProgressStep.CancelFailed : ProgressStep.CloseFailed, e.getMessage());
                    }
                } catch (ProcessingException e) {
                    progress = updateProgress(progress,
                            isCancel ? ProgressStep.CancelFailed : ProgressStep.CloseFailed, e.getMessage());
                }
            } else if (currentLRA != null && compensatorLink != null) {
                log.info("[compensatorLink]: " + compensatorLink);
//                narayanaLRAClient.enlistCompensator(currentLRA, 0L, compensatorLink, new StringBuilder());
            }

            if (response.getStatus() == Response.Status.OK.getStatusCode()
                    && resourceInfo.getResourceMethod() != null
                    && NarayanaLRAClient.isAsyncCompletion(resourceInfo.getResourceMethod())) {
                LRALogger.i18nLogger.warn_lraParticipantqForAsync(
                        resourceInfo.getResourceMethod().getDeclaringClass().getName(),
                        resourceInfo.getResourceMethod().getName(),
                        Response.Status.ACCEPTED.getStatusCode(),
                        Response.Status.OK.getStatusCode());
            }

            /*
             * report any failed steps (ie if progress contains any failures) to the caller.
             * If either filter encountered a failure they may have completed partial actions, and
             * we need tell the caller which steps failed and which ones succeeded. We use a
             * different warning code for each scenario:
             */
            if (progress != null) {
                String failureMessage = processLRAOperationFailures(progress);

                if (failureMessage != null) {
                    LRALogger.logger.warn(failureMessage); // any other failure(s) will already have been logged
                }
            }
        } finally {
            if (suspendedLRA != null) {
                Current.push((URI) suspendedLRA);
            }

            lraId = Current.peek();
            if (lraId != null) {
                response.getHeaders().putSingle(LRA_HTTP_CONTEXT_HEADER, lraId.toASCIIString());
            } else {
                response.getHeaders().remove(LRA_HTTP_CONTEXT_HEADER);
            }

            Current.popAll();
            Current.removeActiveLRACache(currentLRA);
        }
        return response;
    }

    private boolean isJaxRsCancel(Response response,
                                  Response.Status.Family[] cancelOnFamily,
                                  Response.Status[] cancelOn) {
        int status = response.getStatus();

        if (cancelOnFamily != null) {
            if (Arrays.stream(cancelOnFamily).anyMatch(f -> Response.Status.Family.familyOf(status) == f)) {
                return true;
            }
        }

        if (cancelOn != null) {
            return Arrays.stream(cancelOn).anyMatch(f -> status == f.getStatusCode());
        }

        return false;
    }

    private ArrayList<Progress> updateProgress(ArrayList<Progress> progress, ProgressStep step, String reason) {
        if (reason == null) {
            LRALogger.logger.debug(step.toString());
        } else {

            if (progress == null) {
                progress = new ArrayList<>();
            }

            progress.add(new Progress(step, reason));
        }

        return progress;
    }

    private boolean progressDoesNotContain(ArrayList<Progress> progress, ProgressStep step) {
        return progress.stream().noneMatch(p -> p.progress == step);
    }

    private static class Progress {
        static EnumSet<ProgressStep> failures = EnumSet.of(
                ProgressStep.LeaveFailed,
                ProgressStep.StartFailed,
                ProgressStep.JoinFailed,
                ProgressStep.CloseFailed,
                ProgressStep.CancelFailed);

        ProgressStep progress;
        String reason;

        public Progress(ProgressStep progress, String reason) {
            this.progress = progress;
            this.reason = reason;
        }

        public boolean wasSuccessful() {
            return !failures.contains(progress);
        }
    }

    private enum ProgressStep {
        Left("leave succeeded"),
        LeaveFailed("leave failed"),
        Started("start succeeded"),
        StartFailed("start failed"),
        Joined("join succeeded"),
        JoinFailed("join failed"),
        Ended("end succeeded"),
        CloseFailed("close failed"),
        CancelFailed("cancel failed");

        final String status;

        ProgressStep(final String status) {
            this.status = status;
        }

        @Override
        public String toString() {
            return status;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T extends Collection<?>> T cast(Object obj) {
        return (T) obj;
    }

    private static void makeLink(StringBuilder b, String key, URI value) {
        if (key == null || value == null) {
            return;
        }

        String uri = value.toASCIIString();
        Link link = Link.fromUri(uri).title(key + " URI").rel(key).type(MediaType.TEXT_PLAIN).build();

        if (b.length() != 0) {
            b.append(',');
        }

        b.append(link);
    }

    private Response sendRequest(String httpMethod,
                                 String path,
                                 MultivaluedMap<String, String> headers,
                                 MultivaluedMap<String, String> queryParameters) {
        Response response = null;

        try {
            String targetUrl = config.getProxy().getUrl() + path;
            WebTarget target = ClientBuilder.newClient()
                    .target(targetUrl);


            for (Map.Entry<String, List<String>> entry : queryParameters.entrySet()) {
                target = target.queryParam(entry.getKey(), entry.getValue().get(0));
            }

            Builder builder = target
                    .request();

            headers.forEach((s, strings) -> {
                System.out.println("Header: " + s + " Value: " + strings.get(0));
                if (!s.equals("Content-Length")) {
                    builder.header(s, strings.get(0));
                }
            });


            switch (httpMethod.toUpperCase()) {
                case "GET":
                    response = builder.get();
                    break;
                case "POST":
                    response = builder.post(null);
                    break;
                case "PUT":
                    response = builder.put(null);
                    break;
                case "DELETE":
                    response = builder.delete();
                    break;
                case "PATCH":
                    response = builder.method("PATCH", Entity.text(""));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported HTTP method: " + httpMethod);
            }

            return response;

        } catch (IllegalArgumentException e) {
            log.severe("[sendRequest] Invalid URI: " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid URI in request: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            log.severe("[sendRequest] Exception: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .header("X-Error-Message", e.getMessage())
                    .entity("Proxy error occurred")
                    .build();
        }
    }

    private LRAProxyConfig loadYamlConfig(String filePath) {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream(new File(filePath))) {
            return yaml.loadAs(inputStream, LRAProxyConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load YAML: " + filePath, e);
        }
    }


    private boolean isYamlOK(LRAProxyConfig config) throws RuntimeException {
        List<LRAProxyRouteConfig> controls = config.getProxy().getLra();
        controls.forEach(control -> {
            int index = controls.indexOf(control);
            String prefix = "Error in lraControls[" + index + "]: ";

            if (control.getPath() == null || control.getPath().isEmpty()) {
                throw new RuntimeException(prefix + "'path' is missing or empty");
            }

            if (control.getMethod() == null ||
                    !List.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(control.getMethod().toUpperCase())) {
                throw new RuntimeException(prefix + "'httpMethod' must be a valid HTTP method");
            }

            boolean hasSettings = control.getLraSettings() != null;
            boolean hasMethodType = control.getLraMethod() != null;

//            if (hasSettings && hasMethodType) {
//                throw new RuntimeException(prefix + "Both 'lraSettings' and 'lraMethod' cannot be present at the same time");
//            }

            if (!hasSettings && !hasMethodType) {
                throw new RuntimeException(prefix + "One of 'lraSettings' or 'lraMethod' must be defined");
            }

            if (hasMethodType &&
                    !List.of(MethodType.COMPENSATE,
                                    MethodType.COMPLETE,
                                    MethodType.FORGET,
                                    MethodType.STATUS,
                                    MethodType.LEAVE,
                                    MethodType.AFTER)
                            .contains(control.getLraMethod())) {
                throw new RuntimeException(prefix + "Invalid 'lraMethod': " + control.getLraMethod());
            }


            if (hasSettings) {
                if (control.getLraSettings().getType() == null) {
                    throw new RuntimeException(prefix + "'lraSettings.type' must not be null");
                }
            }
        });

        return true;
    }

    private Map<String, LRARoute> getLraRouteMap() {
        Map<String, LRARoute> controlsByPath = new HashMap<>();
        if (config != null && config.getProxy() != null && config.getProxy().getLra() != null) {
            for (LRAProxyRouteConfig control : config.getProxy().getLra()) {
                String rawPath = control.getPath();
                if (rawPath != null) {
                    String normalizedPath = rawPath.startsWith("/") ? rawPath : "/" + rawPath;

                    String method = control.getMethod();
                    LRASettings settings = control.getLraSettings();
                    MethodType methodType = control.getLraMethod();

                    LRARoute route = new LRARoute(method, settings, methodType);
                    controlsByPath.put(normalizedPath, route);
                }
            }
        }
        return controlsByPath;
    }

    private URI toURI(String uri) throws URISyntaxException {
        return uri == null ? null : new URI(uri);
    }

    public Map<String, String> getTerminationUris(String pathPrefix, String baseUri, Long timeout) {
        Map<String, String> paths = new HashMap<>();
        String timeoutValue = timeout != null ? Long.toString(timeout) : "0";

        lraRouteMap.forEach((key, value) -> {
            if (key.startsWith(pathPrefix)) {
                String methodTypeString = "";
                if (value.getMethodType() != null) {
                    switch (value.getMethodType()) {
                        case COMPENSATE -> {
                            methodTypeString = COMPENSATE;
                            paths.put(TIMELIMIT_PARAM_NAME, timeoutValue);
                        }
                        case COMPLETE -> {
                            methodTypeString = COMPLETE;
                            paths.put(TIMELIMIT_PARAM_NAME, timeoutValue);
                        }
                        case STATUS -> methodTypeString = STATUS;
                        case FORGET -> methodTypeString = FORGET;
                        case LEAVE -> methodTypeString = LEAVE;
                        case AFTER -> methodTypeString = AFTER;
                        default -> throw new IllegalStateException("Unsupported methodType: " + value.getMethodType());
                    }
                    paths.put(methodTypeString, baseUri + key);
                }
            }
        });

        StringBuilder linkHeaderValue = new StringBuilder();
        if (!paths.isEmpty()) {
            paths.forEach((k, v) -> {
                try {
                    makeLink(linkHeaderValue, k, toURI(v));
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            });
            paths.put("Link", linkHeaderValue.toString());
        }

        return paths;
    }

    private boolean isTxInvalid(URI lraId, boolean shouldNotBeNull) {
        if (lraId == null && shouldNotBeNull) {
            return true;
        } else if (lraId != null && !shouldNotBeNull) {
            return true;
        }

        return false;
    }

    // convert the list of steps carried out by the filters into a warning message
    // the successful operations are logged at debug and the unsuccessful operations are reported back to the caller.
    // The reason we log multiple failures is that one failure can trigger other operations that may also fail,
    // eg enlisting with an LRA could fail followed by a failure to cancel the LRA
    private String processLRAOperationFailures(ArrayList<Progress> progress) {
        StringJoiner badOps = new StringJoiner(", ");
        StringBuilder code = new StringBuilder("-");

        progress.forEach(p -> {
            code.append(p.progress.ordinal());
            badOps.add(String.format("%s (%s)", p.progress.name(), p.reason));
        });

        /*
         * Previously we returned a string which enabled the reader to distinguish between successful and
         * unsuccessful state transitions but a more fruitful approach is to report problems via i18n message ids
         * and leave successful ops and implicit
         */

        if (badOps.length() != 0) {
            return LRALogger.i18nLogger.warn_LRAStatusInDoubt(String.format("%s: %s", code, badOps));
        }

        return null;
    }

    private String getBasePath(String requestPath) {
        String[] parts = requestPath.split("/");

        if (parts.length <= 2) {
            return parts[0];
        } else {
            return String.join("/", Arrays.copyOfRange(parts, 0, parts.length - 1));
        }
    }
}
