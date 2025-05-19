package io.skoshchi;

import io.narayana.lra.Current;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.logging.LRALogger;
import io.quarkus.runtime.StartupEvent;
import io.skoshchi.exception.LRAProxyConfigException;
import io.skoshchi.yaml.LRAMethodType;
import io.skoshchi.yaml.LRAProxyConfig;
import io.skoshchi.yaml.LRAProxyRouteConfig;
import io.skoshchi.yaml.LRASettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation.Builder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.narayana.lra.LRAConstants.AFTER;
import static io.narayana.lra.LRAConstants.COMPENSATE;
import static io.narayana.lra.LRAConstants.COMPLETE;
import static io.narayana.lra.LRAConstants.FORGET;
import static io.narayana.lra.LRAConstants.LEAVE;
import static io.narayana.lra.LRAConstants.STATUS;
import static io.narayana.lra.LRAConstants.TIMELIMIT_PARAM_NAME;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_PARENT_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_RECOVERY_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.MANDATORY;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.NESTED;

@Path("")
@ApplicationScoped
public class LRAProxy {
    private static final Logger LOG = Logger.getLogger(LRAProxy.class.getName());
    private static final String PATH = "path";

    @ConfigProperty(name = "lra.proxy.config-path")
    String configPath;

    NarayanaLRAClient narayanaLRAClient;
    private LRAProxyConfig config;
    private Map<String, LRARoute> lraRouteMap;

    public void init(@Observes StartupEvent ev) {
        config = loadYamlConfig(configPath);
        lraRouteMap = getLraRouteMap();
        narayanaLRAClient = new NarayanaLRAClient(URI.create(config.getLraCoordinatorUrl()));
    }

    @GET
    @Path("{path:.*}")
    public Response proxyGet(@Context HttpHeaders httpHeaders,
                             @Context UriInfo info,
                             @PathParam(PATH) String path) {
        return handleRequest(httpHeaders, info, HttpMethod.GET, path);
    }

    @POST
    @Path("{path:.*}")
    public Response proxyPost(@Context HttpHeaders httpHeaders,
                              @Context UriInfo info,
                              @PathParam(PATH) String path) {
        return handleRequest(httpHeaders, info, HttpMethod.POST, path);
    }

    @PUT
    @Path("{path:.*}")
    public Response proxyPut(@Context HttpHeaders httpHeaders,
                             @Context UriInfo info,
                             @PathParam(PATH) String path) {
        return handleRequest(httpHeaders, info, HttpMethod.PUT, path);
    }

    @DELETE
    @Path("{path:.*}")
    public Response proxyDelete(@Context HttpHeaders httpHeaders,
                                @Context UriInfo info,
                                @PathParam(PATH) String path) {
        return handleRequest(httpHeaders, info, HttpMethod.DELETE, path);
    }

    @PATCH
    @Path("{path:.*}")
    public Response proxyPatch(@Context HttpHeaders httpHeaders,
                               @Context UriInfo info,
                               @PathParam(PATH) String path) {
        return handleRequest(httpHeaders, info, HttpMethod.PATCH, path);
    }

    public Response handleRequest(HttpHeaders httpHeaders, UriInfo info, String httpMethod, String path) {
        path = path.startsWith("/") ? path : "/" + path;

        LRARoute lraRoute = lraRouteMap.get(path);
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>(httpHeaders.getRequestHeaders());
        MultivaluedMap<String, String> queryParameters = info.getQueryParameters();

        if (lraRoute == null) {
            // no-op
            return sendRequest(httpMethod, path, headers, queryParameters);
        }

        LRASettings lraSettings = lraRoute.getSettings();
        LRA.Type type = lraSettings != null ? lraSettings.getType() : null;
        String actionName = "LRAProxy request [%s %s]".formatted(httpMethod, path);
        URI lraId = Current.peek();
        URI newLRA = null;
        Long timeout = 0L;
        ChronoUnit timeUnit = ChronoUnit.SECONDS;

        URI suspendedLRA = null;
        URI incomingLRA = null;
        URI recoveryUrl;
        boolean isLongRunning = !(lraSettings != null && lraSettings.isEnd());
        boolean requiresActiveLRA = false;
        ArrayList<Progress> progress = null;
        Response.Status.Family[] cancelOnFamily = null;
        Response.Status[] cancelOn = null;

        if (lraSettings != null) {
            cancelOnFamily = Optional.ofNullable(lraSettings.getCancelOnFamily())
                .map(list -> list.toArray(new Response.Status.Family[0]))
                .orElse(new Response.Status.Family[0]);

            cancelOn = Optional.ofNullable(lraSettings.getCancelOn())
                .map(list -> list.toArray(new Response.Status[0]))
                .orElse(new Response.Status[0]);

            if (lraSettings.getTimeLimit() != null) {
                timeout = lraSettings.getTimeLimit();
            }

            if (lraSettings.getTimeUnit() != null) {
                timeUnit = lraSettings.getTimeUnit();
            }
        }

        LRAMethodType lraMethodType = lraRouteMap.get(path).getMethodType();
        boolean endAnnotation = lraRouteMap.containsKey(path) && lraMethodType != null;

        URI currentLRA = null;
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

            if (lraMethodType != null && lraMethodType.equals(LRAMethodType.LEAVE)) {

                Map<String, String> terminateURIs = getTerminationUris(getBasePath(path), config.getUrl(), timeout);
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
            }
        }

        boolean isNotTransactional = false;

        if (type == null) {
            if (!endAnnotation) {
                Current.clearContext(headers);
            }

            if (incomingLRA != null) {
                Current.push(incomingLRA);
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

                                newLRA = lraId = narayanaLRAClient.startLRA(incomingLRA, actionName, timeout, timeUnit);

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
                            newLRA = lraId = narayanaLRAClient.startLRA(null, actionName, timeout, timeUnit);


                            if (newLRA == null) {
                                // startLRA will have called abortWith on the request context
                                // the failure and any previous actions (the leave request) will be reported via the response filter
                                return sendRequest(httpMethod, path, headers, queryParameters);
                            }
                        }

                        break;
                    case REQUIRES_NEW:
                        suspendedLRA = incomingLRA;

                        if (progress == null) {
                            progress = new ArrayList<>();
                        }
                        newLRA = lraId = narayanaLRAClient.startLRA(null, actionName, timeout, timeUnit);
                        if (newLRA == null) {
                            // startLRA will have called abortWith on the request context
                            // the failure and any previous actions (the leave request) will be reported via the response filter
                            return sendRequest(httpMethod, path, headers, queryParameters);
                        }

                        break;
                    case SUPPORTS:
                        lraId = incomingLRA;

                        // incomingLRA will be resumed if not null

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

                // store state with the current thread
                Current.updateLRAContext(lraId, headers); // make the current LRA available to the called method

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

                if (!endAnnotation) { // don't enlist for methods marked with Compensate, Complete or Leave
                    Map<String, String> terminateURIs = getTerminationUris(getBasePath(path), config.getUrl(), timeout);

                    String timeLimitStr = terminateURIs.get(TIMELIMIT_PARAM_NAME);
                    long timeLimit = timeLimitStr == null ? 0L : Long.parseLong(timeLimitStr);

                    if (terminateURIs.containsKey("Link")) {
                        try {
                            StringBuilder linkHeaderValue = new StringBuilder();
                            makeLink(linkHeaderValue, COMPENSATE, toURI(terminateURIs.get(COMPENSATE)));
                            makeLink(linkHeaderValue, COMPLETE, toURI(terminateURIs.get(COMPLETE)));
                            makeLink(linkHeaderValue, FORGET, toURI(terminateURIs.get(FORGET)));
                            makeLink(linkHeaderValue, LEAVE, toURI(terminateURIs.get(LEAVE)));
                            makeLink(linkHeaderValue, AFTER, toURI(terminateURIs.get(AFTER)));
                            makeLink(linkHeaderValue, STATUS, toURI(terminateURIs.get(STATUS)));

                            compensatorLink = linkHeaderValue.toString();
                            StringBuilder previousParticipantData = new StringBuilder();

                            recoveryUrl = narayanaLRAClient.enlistCompensator(lraId, timeLimit, compensatorLink, previousParticipantData);

                            progress = updateProgress(progress, ProgressStep.Joined, null);
                            headers.putSingle(LRA_HTTP_RECOVERY_HEADER,
                                Pattern.compile("^\"|\"$").matcher(recoveryUrl.toASCIIString()).replaceAll(""));

                        } catch (WebApplicationException e) {
                            String reason = e.getMessage();

                            progress = updateProgress(progress, ProgressStep.JoinFailed, reason);
                            return Response.status(e.getResponse().getStatus())
                                .entity("%s: %s, progress: %s".formatted(e.getClass().getSimpleName(), reason, progress))
                                .build();

                        } catch (URISyntaxException e) {
                            progress = updateProgress(progress, ProgressStep.JoinFailed, e.getMessage()); // one or more of the participant end points was invalid
                            return Response.status(Response.Status.BAD_REQUEST.getStatusCode())
                                .entity("%s %s: %s, progress: %s".formatted(lraId, e.getClass().getSimpleName(), e.getMessage(), progress))
                                .build();
                        } catch (ProcessingException e) {
                            progress = updateProgress(progress, ProgressStep.JoinFailed, e.getMessage()); // a remote coordinator was unavailable
                            return Response.status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
                                .entity("%s %s, progress: %s".formatted(e.getClass().getSimpleName(), e.getMessage(), progress))
                                .build();
                        }
                    } else if (requiresActiveLRA && narayanaLRAClient.getStatus(lraId) != LRAStatus.Active) {
                        Current.clearContext(headers);
                        Current.pop(lraId);
                        suspendedLRA = null;

                        if (type == MANDATORY) {
                            return Response.status(Response.Status.PRECONDITION_FAILED.getStatusCode())
                                .entity("LRA should have been active: " + progress)
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
                    LRALogger.i18nLogger.warn_lraFilterContainerRequest("ProcessingException: " + e.getMessage(),
                        actionName, currentLRA.toASCIIString());

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
                Current.push(suspendedLRA);
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
        Response response;

        try {
            String targetUrl = config.getUrl() + path;
            WebTarget target = ClientBuilder.newClient()
                .target(targetUrl);


            for (Map.Entry<String, List<String>> entry : queryParameters.entrySet()) {
                target = target.queryParam(entry.getKey(), entry.getValue().get(0));
            }

            Builder builder = target
                .request();

            headers.forEach((s, strings) -> {
                if (!s.equals("Content-Length")) {
                    builder.header(s, strings.get(0));
                }
            });


            switch (httpMethod) {
                case HttpMethod.GET:
                    response = builder.get();
                    break;
                case HttpMethod.POST:
                    response = builder.post(null);
                    break;
                case HttpMethod.PUT:
                    response = builder.put(null);
                    break;
                case HttpMethod.DELETE:
                    response = builder.delete();
                    break;
                case HttpMethod.PATCH:
                    response = builder.method("PATCH", Entity.text(""));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported HTTP method: " + httpMethod);
            }

            return response;

        } catch (IllegalArgumentException e) {
            LOG.severe("[sendRequest] Invalid URI: " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("Invalid URI in request: " + e.getMessage())
                .build();
        } catch (Exception e) {
            LOG.severe("[sendRequest] Exception: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .header("X-Error-Message", e.getMessage())
                .entity("Proxy error occurred")
                .build();
        }
    }

    private LRAProxyConfig loadYamlConfig(String filePath) throws LRAProxyConfigException {
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor ctor = new Constructor(LRAProxyConfig.class, loaderOptions);

        HyphenToCamelPropertyUtils propUtils = new HyphenToCamelPropertyUtils();
        propUtils.setSkipMissingProperties(true);
        ctor.setPropertyUtils(propUtils);

        Yaml yaml = new Yaml(ctor);

        try (InputStream in = new FileInputStream(filePath)) {
            LRAProxyConfig lraProxyConfig = yaml.loadAs(in, LRAProxyConfig.class);

            validateLRAProxyConfigurationYaml(lraProxyConfig);

            return lraProxyConfig;
        } catch (IOException e) {
            throw new LRAProxyConfigException("Failed to load YAML: " + filePath, e);
        }
    }

    private static class HyphenToCamelPropertyUtils extends PropertyUtils {
        @Override
        public Property getProperty(Class<?> type, String name) {
            String camel = Stream.of(name.split("-"))
                .map(s -> s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .collect(Collectors.joining());
            camel = Character.toLowerCase(camel.charAt(0)) + camel.substring(1);
            return super.getProperty(type, camel);
        }
    }

    private void validateLRAProxyConfigurationYaml(LRAProxyConfig config) throws LRAProxyConfigException {
        if (config == null || config.getLra() == null || config.getLra().isEmpty()) {
            throw new LRAProxyConfigException("LRA Proxy YAML configuration is missing or empty");
        }
        List<LRAProxyRouteConfig> lraProxyRouteConfigs = config.getLra();
        for (int i = 0; i < lraProxyRouteConfigs.size(); i++) {
            LRAProxyRouteConfig lraProxyRouteConfig = lraProxyRouteConfigs.get(i);
            String prefix = "Error in LRA Proxy configuration[%d]: ".formatted(i);

            if (lraProxyRouteConfig.getPath() == null || lraProxyRouteConfig.getPath().isEmpty()) {
                throw new LRAProxyConfigException(prefix + "'path' is missing or empty");
            }

            String httpMethod = lraProxyRouteConfig.getHttpMethod();
            if (httpMethod == null) {
                throw new LRAProxyConfigException(prefix + "'http-method' must be defined");
            }

            switch (httpMethod) {
                case HttpMethod.GET:
                case HttpMethod.POST:
                case HttpMethod.PUT:
                case HttpMethod.DELETE:
                case HttpMethod.PATCH:
                case HttpMethod.HEAD:
                case HttpMethod.OPTIONS:
                    break;
                default:
                    throw new LRAProxyConfigException(prefix + "'http-method' must be a valid HTTP method in upper case (e.g., GET). Unknown value: " + httpMethod);
            }

            boolean hasSettings = lraProxyRouteConfig.getLraSettings() != null;
            boolean hasMethodType = lraProxyRouteConfig.getLraMethod() != null;

            if (!hasSettings && !hasMethodType) {
                throw new LRAProxyConfigException(prefix + "One of 'lra-settings' or 'lra-method' must be defined");
            }

            if (hasMethodType && lraProxyRouteConfig.getLraMethod() == null) {
                throw new LRAProxyConfigException(prefix + "Invalid 'lra-method': " + lraProxyRouteConfig.getLraMethod());
            }

            if (hasSettings && lraProxyRouteConfig.getLraSettings().getType() == null) {
                throw new LRAProxyConfigException(prefix + "'lra-settings.type' must not be null");
            }
        }
    }

    private Map<String, LRARoute> getLraRouteMap() {
        Map<String, LRARoute> controlsByPath = new HashMap<>();
        for (LRAProxyRouteConfig lraProxyRouteConfig : config.getLra()) {
            String rawPath = lraProxyRouteConfig.getPath();
            if (rawPath != null) {
                String normalizedPath = rawPath.startsWith("/") ? rawPath : "/" + rawPath;

                String method = lraProxyRouteConfig.getHttpMethod();
                LRASettings settings = lraProxyRouteConfig.getLraSettings();
                LRAMethodType LRAMethodType = lraProxyRouteConfig.getLraMethod();

                LRARoute route = new LRARoute(method, settings, LRAMethodType);
                controlsByPath.put(normalizedPath, route);
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
            if (key.startsWith(pathPrefix + "/") || key.equals(pathPrefix)) {
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
