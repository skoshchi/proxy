package io.skoshchi;

import io.narayana.lra.AnnotationResolver;
import io.narayana.lra.Current;
import io.narayana.lra.client.LRAParticipantData;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.client.internal.proxy.nonjaxrs.LRAParticipant;
import io.narayana.lra.client.internal.proxy.nonjaxrs.LRAParticipantRegistry;
import io.narayana.lra.filter.ServerLRAFilter;
import io.narayana.lra.logging.LRALogger;
import io.skoshchi.yaml.LRAProxyConfig;
import io.skoshchi.yaml.LRAProxyRouteConfig;
import io.skoshchi.yaml.LRASettings;
import io.skoshchi.yaml.MethodType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.client.Invocation.Builder;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.lra.annotation.*;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.annotation.ws.rs.Leave;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static io.narayana.lra.LRAConstants.*;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.*;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.MANDATORY;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.NESTED;

@Path("")
@ApplicationScoped
public class LRAProxy {
    private final String LRA_HTTP_HEADER = "Long-Running-Action";
    private final String STATUS_CODE_QUERY_NAME = "Coerce-Status";
    private static final String CANCEL_ON_FAMILY_PROP = "CancelOnFamily";
    private static final String CANCEL_ON_PROP = "CancelOn";

    private static final Logger log = Logger.getLogger(LRAProxy.class.getName());

    @Context
    protected ResourceInfo resourceInfo;

    @Inject
    NarayanaLRAClient narayanaLRAClient;

    @Inject
    LRAParticipantData data;

    @Inject
    private LRAParticipantRegistry lraParticipantRegistry;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "proxy.config-path")
    public String configPath;

    private LRAProxyConfig config;
    private Map<String, MapByLRAPath> controlsByPath = new HashMap<>();
    private Map<String, Map<MethodType, LRACompensator>> compensatorsByPath = new HashMap<>();

    @PostConstruct
    public void init() {
        config = loadYamlConfig(configPath);
        if (!isYamlOK(config)) {
            throw new IllegalStateException("YAML configuration is invalid: " + configPath);
        }
        controlsByPath = getControlsByPath();
        compensatorsByPath = getCompensatorsByPathToResource();
    }


    @GET
    @Path("{path:.*}")
    public Response proxyGet(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                             @PathParam("path") String path) {
        return handleRequest("GET", path, 200);
    }

    @POST
    @Path("{path:.*}")
    public Response proxyPost(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                              @PathParam("path") String path) {
        return handleRequest("POST", path, 200);
    }

    @PUT
    @Path("{path:.*}")
    public Response proxyPut(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                             @DefaultValue("200") @QueryParam(STATUS_CODE_QUERY_NAME) int coerceStatus,
                             @PathParam("path") String path) {
        return handleRequest("PUT", path, coerceStatus);
    }

    @DELETE
    @Path("{path:.*}")
    public Response proxyDelete(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                                @PathParam("path") String path) {
        return handleRequest("DELETE", path, 200);
    }

    @PATCH
    @Path("{path:.*}")
    public Response proxyPatch(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                               @PathParam("path") String path) {
        return handleRequest("PATCH", path, 200);
    }

    public Response handleRequest(String httpMethod, String path, int coerceStatus) {
        path = path.startsWith("/") ? path : "/" + path;

        log.info("[handleRequest] " + httpMethod +
                " Incoming path: " + path);

        if (!controlsByPath.containsKey(path)) {
            throw new IllegalStateException("No path found in yaml: " + path);
        }
        MapByLRAPath lraProxyRouteConfig = controlsByPath.get(path);

        Method method = resourceInfo.getResourceMethod();
        LRA.Type type = lraProxyRouteConfig.getSettings() != null ? lraProxyRouteConfig.getSettings().getType() : null;

        LRA transactional = AnnotationResolver.resolveAnnotation(LRA.class, method);

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

        Response.Status.Family[] cancelOnFamily = Optional.ofNullable(lraProxyRouteConfig.getSettings().getCancelOnFamily())
                .map(list -> list.toArray(new Response.Status.Family[0]))
                .orElse(new Response.Status.Family[0]);

        Response.Status[] cancelOn = Optional.ofNullable(lraProxyRouteConfig.getSettings().getCancelOn())
                .map(list -> list.toArray(new Response.Status[0]))
                .orElse(new Response.Status[0]);


        boolean endAnnotation =  compensatorsByPath.containsKey(path);

        URI suspendLRA = null;
        URI currentLRA = null;
        URI parentLRA = null;
        URI terminateLRA = null;
        MultivaluedMap<String, String> headers = null;

        Response response = Response.ok().build();

        if (lraId != null) {
            incomingLRA = lraId;

            if (compensatorsByPath.get(path).containsKey(MethodType.LEAVE)) {

                Map<String, String> terminateURIs = buildTerminationUrisFromCompensators(compensatorsByPath, path, coerceStatus);
                String compensatorId = terminateURIs.get("Link");

                if (compensatorId == null) {
                    LRALogger.i18nLogger.warn_lraFilterContainerRequest("Missing complete or compensate annotations",
                            method.getDeclaringClass().getName() + "#" + method.getName(),
                            lraId == null ? "context" : lraId.toString());

                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("Missing complete or compensate annotations")
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

        if (type == null) {
            if (!endAnnotation) {
                Current.popAll();
            }

            if (incomingLRA != null) {
                Current.push(incomingLRA);
                suspendLRA = incomingLRA;
                currentLRA = incomingLRA;
                Current.addActiveLRACache(incomingLRA);
            }

            return Response.status(200).entity("not transactional").build();
        }


        if (endAnnotation && incomingLRA == null) {
            return Response.status(200).entity("endAnnotation").build();
        }

        if (incomingLRA != null) {
            // set the parent context header
            try {
                parentLRA = toURI(Current.getFirstParent(incomingLRA));
                headers.putSingle(LRA_HTTP_PARENT_CONTEXT_HEADER, Current.getFirstParent(incomingLRA));
            } catch (UnsupportedEncodingException e) {
                return Response.status(Response.Status.PRECONDITION_FAILED.getStatusCode())
                        .entity(String.format("incoming LRA %s contains an invalid parent: %s", incomingLRA, e.getMessage()))
                        .build();
            } catch (URISyntaxException e) {
                return Response.status(Response.Status.NOT_FOUND.getStatusCode())
                        .entity(String.format("incoming LRA %s contains an invalid parent: %s", incomingLRA, e.getMessage()))
                        .build();
            }
        }

        switch (type) {
            case MANDATORY:
                if (isTxInvalid(type, incomingLRA, true, progress)) {
                    return Response.status(Response.Status.PRECONDITION_FAILED.getStatusCode())
                            .entity(type.name() + " but no tx")
                            .build();
                }

                lraId = incomingLRA;
                requiresActiveLRA = true;

                break;
            case NEVER: // a txn must not be present
                if (isTxInvalid(type, incomingLRA, false, progress)) {
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
                        response.getHeaders().add(LRA_HTTP_PARENT_CONTEXT_HEADER, incomingLRA.toASCIIString());

                        // if there is an LRA present nest a new LRA under it
                        suspendedLRA = incomingLRA;

                        if (progress == null) {
                            progress = new ArrayList<>();
                        }

                        newLRA = lraId = narayanaLRAClient.startLRA(incomingLRA, method.getDeclaringClass().getName() + "#" + method.getName(), timeout, timeUnit);

                        if (newLRA == null) {
                            // startLRA will have called abortWith on the request context
                            // the failure plus any previous actions (the leave request) will be reported via the response filter
                            return Response.ok().entity("newLRA == null").build();
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
                        return Response.ok().entity("newLRA == null").build();
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
                    return Response.ok().entity("newLRA == null").build();
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
            Current.popAll();

            return Response.ok().entity("non transactional").build();
        }

        if (!isLongRunning) {
            terminateLRA = lraId;
        }

        // store state with the current thread
        Current.push(lraId);
        if (newLRA != null) {
            if (suspendedLRA != null) {
                suspendedLRA = incomingLRA;
            }
        }

        Current.push(lraId);

        try {
            narayanaLRAClient.setCurrentLRA(lraId); // make the current LRA available to the called method
        } catch (Exception e) {
            // should not happen since lraId has already been validated
            // (perhaps we should not use the client API to set the context)
            return Response.status(Response.Status.BAD_REQUEST.getStatusCode())
                    .entity(e.getMessage())
                    .build();
        }

        String compensatorLink = null;

        if (!endAnnotation) { // don't enlist for methods marked with Compensate, Complete or Leave
            Map<String, String> terminateURIs = buildTerminationUrisFromCompensators(compensatorsByPath, path, coerceStatus);

            String timeLimitStr = terminateURIs.get(TIMELIMIT_PARAM_NAME);
            long timeLimit = timeLimitStr == null ? 0L : Long.parseLong(timeLimitStr);

            LRAParticipant participant = lraParticipantRegistry != null ?
                    lraParticipantRegistry.getParticipant(resourceInfo.getResourceClass().getName()) : null;

            if (terminateURIs.containsKey("Link") || participant != null) {
                try {
                    if (participant != null) {
                        participant.augmentTerminationURIs(terminateURIs, toURI(getPathToResource(path)));
                    }
                Map<MethodType, LRACompensator> compensatorsMap = compensatorsByPath.get(getPathToResource(path));

                    compensatorLink = buildCompensatorURI(
                        toURI(compensatorsMap.containsKey(MethodType.COMPENSATE) ? compensatorsMap.get(MethodType.COMPENSATE).getPath(): null),
                        toURI(compensatorsMap.containsKey(MethodType.COMPLETE) ? compensatorsMap.get(MethodType.COMPLETE).getPath(): null),
                        toURI(compensatorsMap.containsKey(MethodType.FORGET) ? compensatorsMap.get(MethodType.FORGET).getPath(): null),
                        toURI(compensatorsMap.containsKey(MethodType.LEAVE) ? compensatorsMap.get(MethodType.LEAVE).getPath(): null),
                        toURI(compensatorsMap.containsKey(MethodType.AFTER) ? compensatorsMap.get(MethodType.AFTER).getPath(): null),
                        toURI(compensatorsMap.containsKey(MethodType.STATUS) ? compensatorsMap.get(MethodType.STATUS).getPath(): null));

                    StringBuilder previousParticipantData = new StringBuilder();

                    recoveryUrl = narayanaLRAClient.enlistCompensator(lraId, timeLimit, compensatorLink, previousParticipantData);

                    if (previousParticipantData.length() != 0) {
                        // this participant has previously updated the LRAParticipantData bean so make it available for this invocation
                        setUserDefinedData(previousParticipantData.toString());
                    }

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

            // ================= SEND THE REQUEST =================
            response = sendRequest(httpMethod, path, lraId, lraId.toString(), coerceStatus);

            // ================= RESPONSE FILTER =================

            boolean isCancel = isJaxRsCancel(response);
            String userData = getUserDefinedData();

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
                            narayanaLRAClient.cancelLRA(terminateLRA, compensatorLink, getUserDefinedData());
                        } else {
                            narayanaLRAClient.closeLRA(terminateLRA, compensatorLink, getUserDefinedData());
                        }

                        progress = updateProgress(progress, ProgressStep.Ended, null);
                    }
                } catch (WebApplicationException e) {
                    if (e.getResponse().getStatus() == NOT_FOUND.getStatusCode()) {
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
            } else if (currentLRA != null && compensatorLink != null && userData != null) {
                narayanaLRAClient.enlistCompensator(currentLRA, 0L, compensatorLink, new StringBuilder(userData));
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
                String failureMessage =  processLRAOperationFailures(progress);

                if (failureMessage != null) {
                    LRALogger.logger.warn(failureMessage); // any other failure(s) will already have been logged
                }
            }
        } finally {
            if (suspendedLRA != null) {
                Current.push((URI) suspendedLRA);
            }

            lraId = Current.peek();

            Current.popAll();
            Current.removeActiveLRACache(currentLRA);

            return Response.ok(lraId.toString()).build();
        }
    }

    private boolean isJaxRsCancel(Response response) {
        int status = response.getStatus();
        Response.Status.Family[] cancelOnFamily = (Response.Status.Family[]) response.getMetadata().getFirst(CANCEL_ON_FAMILY_PROP);
        Response.Status[] cancelOn = (Response.Status[]) response.getMetadata().getFirst(CANCEL_ON_PROP);

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

    private String getUserDefinedData() {
        try {
            return data != null ? data.getData() : null;
        } catch (ContextNotActiveException e) {
            LRALogger.i18nLogger.warn_missingContexts("CDI bean of type LRAParticipantData is not available", e);
        }

        return null;
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

    private boolean hasCompensatorConfig(String path) {
        if (compensatorsByPath.containsKey(path)) {
            Map<MethodType, LRACompensator> innerMap = compensatorsByPath.get(path);
            return innerMap.containsKey(MethodType.COMPENSATE) || innerMap.containsKey(MethodType.AFTER);
        } else {
            return false;
        }
    }

    private String buildCompensatorURI(URI compensate, URI complete, URI forget, URI leave, URI after, URI status) {
         StringBuilder linkHeaderValue = new StringBuilder();

        makeLink(linkHeaderValue, COMPENSATE, compensate);
        makeLink(linkHeaderValue, COMPLETE, complete);
        makeLink(linkHeaderValue, FORGET, forget);
        makeLink(linkHeaderValue, LEAVE, leave);
        makeLink(linkHeaderValue, AFTER, after);
        makeLink(linkHeaderValue, STATUS, status);

        return linkHeaderValue.toString();
    }

    private void appendLinkIfExists(StringBuilder builder, String key, URI uri) {
        if (uri != null) {
            makeLink(builder, key, uri);
        }
    }

    private boolean progressDoesNotContain(ArrayList<Progress> progress, ProgressStep step) {
        return progress.stream().noneMatch(p -> p.progress == step);
    }

    private URI getFullPathForLraMethodSafe(MethodType methodType) {
        try {
            return getFullPathForLraMethod(methodType);
        } catch (Exception e) {
            return null;
        }
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
        Left ("leave succeeded"),
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
        Link link =  Link.fromUri(uri).title(key + " URI").rel(key).type(MediaType.TEXT_PLAIN).build();

        if (b.length() != 0) {
            b.append(',');
        }

        b.append(link);
    }

    private Response sendRequest(String httpMethod, String path, URI lraId, String successMessage, int coerceStatus) {
        try {
            String targetUrl = config.getProxy().getUrl() + "/" + path;

            if ("PUT".equalsIgnoreCase(httpMethod)) {
                String separator = targetUrl.contains("?") ? "&" : "?";
                targetUrl += separator + STATUS_CODE_QUERY_NAME + "=" + coerceStatus;
            }

            Builder builder = ClientBuilder.newClient()
                    .target(targetUrl)
                    .request();

            if (lraId != null) {
                builder.header(LRA_HTTP_CONTEXT_HEADER, lraId.toASCIIString());
            }

            Response response;
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

            String body = response.readEntity(String.class);
            log.info("[sendRequest] Response: " + body);
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

            if (hasSettings && hasMethodType) {
                throw new RuntimeException(prefix + "Both 'lraSettings' and 'lraMethod' cannot be present at the same time");
            }

            if (!hasSettings && !hasMethodType) {
                throw new RuntimeException(prefix + "One of 'lraSettings' or 'lraMethod' must be defined");
            }

            if (hasMethodType &&
                    !List.of(   MethodType.COMPENSATE,
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

    private Map<String, MapByLRAPath> getControlsByPath() {
        Map<String, MapByLRAPath> controlsByPath = new HashMap<>();
        if (config != null && config.getProxy()!= null && config.getProxy().getLra() != null) {
            for (LRAProxyRouteConfig control : config.getProxy().getLra()) {
                String rawPath = control.getPath();
                if (rawPath != null) {
                    String normalizedPath = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
                    String[] pathParts = normalizedPath.split("/");
                    if (pathParts.length >= 3) {
                        String trimmedPath = "/" + String.join("/", java.util.Arrays.copyOfRange(pathParts, 2, pathParts.length));

                        String method = control.getMethod();
                        LRASettings settings = control.getLraSettings();
                        MethodType methodType = control.getLraMethod();

                        String serviceName = pathParts[1];

                        MapByLRAPath route = new MapByLRAPath(serviceName, method, settings, methodType);
                        controlsByPath.put(trimmedPath, route);
                    }
                }
            }
        }
        return controlsByPath;
    }

    private Map<String, Map<MethodType, LRACompensator>> getCompensatorsByPathToResource() {
        Map<String, Map<MethodType, LRACompensator>> compensators = new HashMap<>();

        if (config != null && config.getProxy()!= null && config.getProxy().getLra() != null) {
            for (LRAProxyRouteConfig control : config.getProxy().getLra()) {
                if(control.getLraMethod() != null) {
                    String pathToResource = getPathToResource(control.getPath());

                    if (compensators.containsKey(pathToResource)) {
                        compensators.get(pathToResource)
                                .put(control.getLraMethod(), new LRACompensator(config.getProxy().getUrl() + "/" + control.getPath(), control.getMethod()));
                    } else {
                        compensators.put(pathToResource, new HashMap<>());
                        compensators.get(pathToResource)
                                .put(control.getLraMethod(), new LRACompensator(config.getProxy().getUrl() + "/"  + control.getPath(), control.getMethod()));
                    }
                }
            }
        }
        return compensators;
    }

    private URI getFullPathForLraMethod(MethodType methodType) {
        if (methodType == null) {
            throw new IllegalArgumentException("MethodType must not be null");
        }

        String targetUrl = config.getProxy().getUrl();
        String actionPath = null;

        for (LRAProxyRouteConfig control : config.getProxy().getLra()) {
            if (control.getLraMethod() != null && control.getLraMethod() == methodType) {
                actionPath = control.getPath();
                break;
            }
        }

        if (actionPath == null) {
            throw new IllegalArgumentException("No LRA control found for MethodType: " + methodType);
        }

        String output = String.format("%s/%s", targetUrl, actionPath);

        try {
            return new URI(output);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to create URI from: " + output, e);
        }
    }

    private List<LRAProxyRouteConfig> findEligibleCompensators(String requestPath) {
        String trimmedPath = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        String[] segments = trimmedPath.split("/");
        String servicePrefix;

        if (segments.length >= 3) {
            servicePrefix = segments[0] + "/" + segments[1];
        } else {
            servicePrefix = trimmedPath;
        }

        List<LRAProxyRouteConfig> eligibleList = new ArrayList<>();
        for (LRAProxyRouteConfig compensator : config.getProxy().getLra()) {
            String compPath = compensator.getPath().substring(0, compensator.getPath().lastIndexOf('/'));
            MethodType lraMethod = compensator.getLraMethod();

            if (lraMethod != null) {
                if (compPath.startsWith("/" + servicePrefix) || compPath.equals(servicePrefix)) {
                    eligibleList.add(compensator);
                }
            }
        }
        return eligibleList;
    }

    private static String getServiceName(String requestPath) {
        String[] segments = requestPath.split("/");
        String serviceName;

        if (segments.length >= 3) {
            serviceName = segments[0] + "/" + segments[1];
        } else {
            serviceName = requestPath;
        }
        return serviceName;
    }

    private String getPathToResource(String requestPath) {
        String[] parts = requestPath.split("/");

        if (parts.length <= 2) {
            return parts[0];
        } else {
            return String.join("/", Arrays.copyOfRange(parts, 1, parts.length - 1));
        }
    }

    private URI toURI(String uri) throws URISyntaxException {
        return uri == null ? null : new URI(uri);
    }
    public static Map<String, String> buildTerminationUrisFromCompensators(
            Map<String, Map<MethodType, LRACompensator>> compensatorsByPath,
            String pathToResource,
            long timeout
    ) {
        Map<String, String> paths = new HashMap<>();
        String timeoutValue = Long.toString(timeout);

        System.out.println("compensatorsByPath.get(pathToResource) = " + compensatorsByPath.get(pathToResource));
        System.out.println("pathToResource = " + pathToResource);
        Map<MethodType, LRACompensator> compensatorMap = compensatorsByPath.get(getServiceName(pathToResource));
        if (compensatorMap == null) {
            throw new IllegalArgumentException("No compensators registered for path: " + pathToResource);
        }

        compensatorMap.forEach((methodType, compensator) -> {
            String uri = compensator.getPath();
            String httpMethod = compensator.getHttpMethod();

            String rel;
            switch (methodType) {
                case COMPENSATE -> rel = COMPENSATE;
                case COMPLETE -> rel = COMPLETE;
                case STATUS -> rel = STATUS;
                case FORGET -> rel = FORGET;
                case LEAVE -> rel = LEAVE;
                case AFTER -> rel = AFTER;
                default -> throw new IllegalStateException("Unsupported methodType: " + methodType);
            }

            String finalUri = uri.contains("?") ? uri + "&" : uri + "?";
            finalUri += "HttpMethodName=" + httpMethod;

            paths.put(rel, finalUri);
        });

        paths.put(TIMELIMIT_PARAM_NAME, timeoutValue);

        StringBuilder linkHeaderValue = new StringBuilder();
        paths.forEach((k, v) -> {
            if (List.of(COMPENSATE, COMPLETE, FORGET, LEAVE, AFTER, STATUS).contains(k)) {
                Link link = Link.fromUri(v)
                        .title(k + " URI")
                        .rel(k)
                        .type(MediaType.TEXT_PLAIN)
                        .build();
                if (linkHeaderValue.length() > 0) {
                    linkHeaderValue.append(",");
                }
                linkHeaderValue.append(link.toString());
            }
        });

        paths.put("Link", linkHeaderValue.toString());
        return paths;
    }

    private boolean isTxInvalid(LRA.Type type, URI lraId,
                                boolean shouldNotBeNull, ArrayList<Progress> progress) {
        if (lraId == null && shouldNotBeNull) {
            return true;
        } else return lraId != null && !shouldNotBeNull;
    }

    private void setUserDefinedData(String userDefinedData) {
        try {
            if (data != null) {
                data.setData(userDefinedData);
            }
        } catch (ContextNotActiveException e) {
            LRALogger.i18nLogger.warn_missingContexts("CDI bean of type LRAParticipantData is not available", e);
        }
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
}
