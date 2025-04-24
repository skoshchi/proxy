package io.skoshchi;

import io.narayana.lra.AnnotationResolver;
import io.narayana.lra.Current;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.filter.ServerLRAFilter;
import io.narayana.lra.logging.LRALogger;
import io.skoshchi.yaml.LRAControl;
import io.skoshchi.yaml.LRAProxyConfigFile;
import io.skoshchi.yaml.MethodType;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.annotation.ws.rs.Leave;
import org.yaml.snakeyaml.Yaml;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.*;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.NESTED;

@Path("")
public class SidecarResource {
    private static final String ABORT_WITH_PROP = "abortWith";

    @Inject
    NarayanaLRAClient narayanaLRAClient;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "proxy.config-path")
    public String configPath;


    private LRAProxyConfigFile config;
    private Map<String, LRAControl> controlsByPath = new HashMap<>();

    @PostConstruct
    public void init() {
        config = loadYamlConfig(configPath);
        controlsByPath = getControlsByPath();
    }

    @Context
    protected ResourceInfo resourceInfo;

    // KEK CHEBUREK!
    // FIX it PLZ
//    private URI lraId;

    @GET
    @Path("{path:.*}")
    @LRA(value = LRA.Type.SUPPORTS, end = true)
    public Response proxyGet(@PathParam("path") String fullPath,
//                             @HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId,
//                             @HeaderParam(LRA_HTTP_ENDED_CONTEXT_HEADER) URI endedLRAId,
//                             @HeaderParam(LRA.LRA_HTTP_PARENT_CONTEXT_HEADER) URI parentLraId,
//                             @HeaderParam(LRA_HTTP_RECOVERY_HEADER) URI recoveryUrl,
                                ContainerRequestContext containerRequestContext
                             ) {
        String[] parts = fullPath.split("/");
        String pathSuffix = parts.length > 0 ? parts[parts.length - 1] : fullPath;

        if (!controlsByPath.containsKey(pathSuffix)) {
            String message = "Path '" + pathSuffix + "' not found in YAML configuration.";
            niceStringOutput(message);
            return Response.status(Response.Status.NOT_FOUND).entity(message).build();
        }
        MultivaluedMap<String, String> headers = containerRequestContext.getHeaders();
        LRAControl lraControl = controlsByPath.get(pathSuffix);
        String lraName = lraControl.getName();

        LRA.Type type = lraControl.getLraSettings().getType();
        Long timeout = lraControl.getLraSettings().getTimeLimit();
        ChronoUnit timeUnit = lraControl.getLraSettings().getTimeUnit();
        boolean end = lraControl.getLraSettings().isEnd();

        URI incomingLRA = null;
        ArrayList<Progress> progress = null;
        Method method = resourceInfo.getResourceMethod();
        URI newLRA = null;
        URI suspendedLRA = null;
        URI lraId = null;
        boolean requiresActiveLRA = false;


        if (headers.containsKey(LRA_HTTP_CONTEXT_HEADER)) {
            try {
                incomingLRA = new URI(Current.getLast(headers.get(LRA_HTTP_CONTEXT_HEADER)));
            } catch (URISyntaxException e) {
                String msg = String.format("header %s contains an invalid URL %s",
                        LRA_HTTP_CONTEXT_HEADER, Current.getLast(headers.get(LRA_HTTP_CONTEXT_HEADER)));

                abortWith(containerRequestContext, null, Response.Status.PRECONDITION_FAILED.getStatusCode(),
                        msg, null);
                return Response.ok("Error").build(); // user error, bail out
            }

            if (AnnotationResolver.isAnnotationPresent(Leave.class, method)) {
                // leave the LRA
                Map<String, String> terminateURIs = NarayanaLRAClient.getTerminationUris(
                        resourceInfo.getResourceClass(), createUriPrefix(containerRequestContext), timeout);
                String compensatorId = terminateURIs.get("Link");

                if (compensatorId == null) {
                    abortWith(containerRequestContext, incomingLRA.toASCIIString(),
                            Response.Status.BAD_REQUEST.getStatusCode(),
                            "Missing complete or compensate annotations", null);
                    return Response.ok("Error").build(); // user error, bail out
                }

                progress = new ArrayList<>();

                try {
                    narayanaLRAClient.leaveLRA(incomingLRA, compensatorId);
                    progress.add(new Progress(ProgressStep.Left, null)); // leave succeeded
                } catch (WebApplicationException e) {
                    progress.add(new Progress(ProgressStep.LeaveFailed, e.getMessage())); // leave may have failed
                    return Response.ok("Error").build(); // user error, bail out

                } catch (ProcessingException e) { // a remote coordinator was unavailable
                    progress.add(new Progress(ProgressStep.LeaveFailed, e.getMessage())); // leave may have failed
                    return Response.ok("Error").build(); // user error, bail out
                }

                // let the participant know which lra he left by leaving the header intact
            }
        }


        // check the incoming request for an LRA context
        if (!headers.containsKey(LRA_HTTP_CONTEXT_HEADER)) {
            Object lraContext = containerRequestContext.getProperty(LRA_HTTP_CONTEXT_HEADER);

            if (lraContext != null) {
                incomingLRA = (URI) lraContext;
            }
        }

        try {
            switch (type) {
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

                            newLRA = lraId = startLRA(containerRequestContext, incomingLRA, method, timeout, progress);

                            if (newLRA == null) {
                                // startLRA will have called abortWith on the request context
                                // the failure plus any previous actions (the leave request) will be reported via the response filter
                                return Response.ok("Error REQUIRED failed").build();
                            }
                        } else {
                            lraId = incomingLRA;
                            // incomingLRA will be resumed
                            requiresActiveLRA = true;
                        }

                    } else {
                        progress = new ArrayList<>();
                        newLRA = lraId = startLRA(containerRequestContext, null, method, timeout, progress);

                        if (newLRA == null) {
                            // startLRA will have called abortWith on the request context
                            // the failure and any previous actions (the leave request) will be reported via the response filter
                            return Response.ok("Error REQUIRED failed").build();
                        }

//                        URI active = Current.peek();
//                        if (active == null) {
//                            lraId = narayanaLRAClient.startLRA(null, lraName, timeout, timeUnit);
//                            niceStringOutput("Started new REQUIRED LRA: " + lraId);
//                            narayanaLRAClient.setCurrentLRA(lraId);
//                        } else {
//                            lraId = narayanaLRAClient.startLRA(parentLraId, lraName, timeout, timeUnit);
//                            niceStringOutput("Started REQUIRED LRA within parent: " + lraId);
//                            narayanaLRAClient.setCurrentLRA(lraId);
//                        }
                    }
                    break;
                case REQUIRES_NEW:
                    suspendedLRA = incomingLRA;

                    if (progress == null) {
                        progress = new ArrayList<>();
                    }
                    newLRA = lraId = startLRA(containerRequestContext, incomingLRA, method, timeout, progress);

                    if (newLRA == null) {
                        // startLRA will have called abortWith on the request context
                        // the failure and any previous actions (the leave request) will be reported via the response filter
                        return Response.ok("Error REQUIRES_NEW failed").build();
                    }
                    niceStringOutput("Started REQUIRES_NEW LRA: " + lraId);
                    break;
            }

            Response result = sendGetRequest(pathSuffix, "I have send this message");

            if (end && lraId != null) {
                narayanaLRAClient.closeLRA(lraId);
                niceStringOutput("Closed LRA after request: " + lraId);
            }

            return Response.ok("Proxy work done").build();
        } catch (Exception e) {
            if (lraId != null) {
                try {
                    narayanaLRAClient.cancelLRA(lraId);
                    niceStringOutput("Canceled LRA due to error: " + lraId);
                } catch (Exception ex) {
                    niceStringOutput("Failed to cancel LRA: " + ex.getMessage());
                }
            }
            return Response.serverError().entity("LRA processing error: " + e.getMessage()).build();
        }
    }


    @PUT
    @Path("/after")
    @AfterLRA
    public Response afterLRA(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        niceStringOutput("AfterLRA called for LRA: " + lraId + " with status: ");
        return Response.ok().build();
    }

    @GET
    @Path("/demo")
    @LRA(LRA.Type.REQUIRES_NEW)
    public Response demoLRAFlow(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String test = "hotel/status-order";
        String[] parts = test.split("/");
        String lastPath = parts.length > 0 ? parts[parts.length - 1] : test;

        niceStringOutput("demo run");
        niceStringOutput(narayanaLRAClient.getCurrent().getPath());

        return Response.ok(lraId).build();
    }


    private void niceStringOutput(String input) {
        System.out.println("===========\n" + input + "\n===========");
    }

    private LRAProxyConfigFile loadYamlConfig(String filePath) {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream(new File(filePath))) {
            config = yaml.loadAs(inputStream, LRAProxyConfigFile.class);
            isYamlOK(config);
            return config;
        } catch (FileNotFoundException e) {
            throw new RuntimeException("YAML not found: " + filePath, e);
        } catch (IOException e) {
            throw new RuntimeException("Error closing stream for file: " + filePath, e);
        } catch (RuntimeException e) {
            throw new RuntimeException("Yaml has a format error: ", e);
        }
    }

    /**
     * Method that checks if Yaml file is correct
     *      name != null
     *      path != null
     *      method == correct http method
     *      lraSettings && lraMethod != null            ||
     *      lraSettings && lraMethod == null            ||
     *      lraSettings == null && lraMethod != null    ||
     *      lraSettings != null && lraMethod == null
     *      lraMethod == correct
     *      lraSettings.type != null
     * @param config
     * @throws RuntimeException
     */
    private void isYamlOK(LRAProxyConfigFile config) throws RuntimeException {
        List<LRAControl> controls = config.getLraProxy().getLraControls();
        controls.forEach(control -> {
            int index = controls.indexOf(control);
            String prefix = "Error in lraControls[" + index + "]: ";

            if (control.getName() == null || control.getName().isEmpty()) {
                throw new RuntimeException(prefix + "'name' is missing or empty");
            }

            if (control.getPath() == null || control.getPath().isEmpty()) {
                throw new RuntimeException(prefix + "'path' is missing or empty");
            }

            if (control.getMethod() == null ||
                    !List.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(control.getMethod().toUpperCase())) {
                throw new RuntimeException(prefix + "'method' must be a valid HTTP method");
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
                    !List.of(MethodType.Compensate, MethodType.Complete, MethodType.Forget, MethodType.Status, MethodType.AfterLRA)
                            .contains(control.getLraMethod())) {
                throw new RuntimeException(prefix + "Invalid 'lraMethod': " + control.getLraMethod());
            }


            if (hasSettings) {
                if (control.getLraSettings().getType() == null) {
                    throw new RuntimeException(prefix + "'lraSettings.type' must not be null");
                }
            }
        });
    }

    /**
     * A universal method for sending a GET request via HOTEL_SERVICE_URL with the specified suffix.
     * @param pathSuffix is a suffix added to the base URL.
     * @param successMessage Message returned if successful.
     * @return Response received from the target service or an error.
     */
    private Response sendGetRequest(String pathSuffix, String successMessage) {
        try {
            String targetUrl = config.getLraProxy().getUrl() + "/" + config.getLraProxy().getServiceName() + "/" + pathSuffix;
            URI targetUri = URI.create(targetUrl);

             HttpRequest request = HttpRequest.newBuilder()
                    .uri(targetUri)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            niceStringOutput(response.body());
            return Response.ok(successMessage).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error during request: " + e.getMessage())
                    .build();
        }
    }

    // the request filter may perform multiple and in failure scenarios the LRA may be left in an ambiguous state:
    // the following structure is used to track progress so that such failures can be reported in the response
    // filter processing
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


    // list of steps (both successful and unsuccessful) performed so far by the request and response filter
    // and is used for error reporting
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

    // the processing performed by the request filter caused the request to abort (without executing application code)
    private void abortWith(ContainerRequestContext containerRequestContext, String lraId, int statusCode,
                           String message, Collection<Progress> reasons) {
        // the response filter will set the entity body
        containerRequestContext.abortWith(Response.status(statusCode).entity(message).build());
        // make the reason for the failure available to the response filter
        containerRequestContext.setProperty(ABORT_WITH_PROP, reasons);

        Method method = resourceInfo.getResourceMethod();
        LRALogger.i18nLogger.warn_lraFilterContainerRequest(message,
                method.getDeclaringClass().getName() + "#" + method.getName(),
                lraId == null ? "context" : lraId);
    }

    private URI toURI(String uri) throws URISyntaxException {
        return uri == null ? null : new URI(uri);
    }

    private String createUriPrefix(ContainerRequestContext containerRequestContext) {
        return ConfigProvider.getConfig().getOptionalValue("narayana.lra.base-uri", String.class)
                .orElseGet(() -> {
                    UriInfo uriInfo = containerRequestContext.getUriInfo();

                    /*
                     * Calculate which path to prepend to the LRA participant methods. If there is more than one matching URI
                     * then the second matched URI comes from either the class level Path annotation or from a sub-resource locator.
                     * In both cases the second matched URI can be used as a prefix for the LRA participant URIs:
                     */
                    List<String> matchedURIs = uriInfo.getMatchedURIs();
                    int matchedURI = (matchedURIs.size() > 1 ? 1 : 0);
                    return uriInfo.getBaseUri() + matchedURIs.get(matchedURI);
                });
    }

        private URI startLRA(ContainerRequestContext containerRequestContext, URI parentLRA, Method method, Long timeout,
                ArrayList<Progress> progress) {
            // timeout should already have been converted to milliseconds
            String clientId = method.getDeclaringClass().getName() + "#" + method.getName();

            try {
                URI lra = narayanaLRAClient.startLRA(parentLRA, clientId, timeout, ChronoUnit.MILLIS, false);
                updateProgress(progress, ProgressStep.Started, null);
                return lra;
            } catch (WebApplicationException e) {
                String msg = e.getResponse().readEntity(String.class);

                updateProgress(progress, ProgressStep.StartFailed, msg);
            }

            return null;
        }

    // add another step to the list of steps performed so far
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
    /**
     * Creates a map of all LRA controls defined in the YAML configuration,
     * by their value {@code path}.
     *
     * <p>Each key in the resulting {@link Map} represents an endpoint,
     * specified in the {@code path} field inside the {@link LRAControl} element, and the value
     * is the corresponding {@link LRAControl} containing all the necessary data
     * for LRA management.</p>
     *
     * <p>If {@code config}, {@code config.getLraProxy()} and the list {@code getLraControls()} is equal to {@code null},
     * then an empty card will be returned.</p>
     *
     * @return a map where the key is {@code path} and the value is the {@link LRAControl} object
     * */
    private Map<String, LRAControl> getControlsByPath() {
        Map<String, LRAControl> controlsByPath = new HashMap<>();

        if (config != null && config.getLraProxy() != null && config.getLraProxy().getLraControls() != null) {
            for (LRAControl control : config.getLraProxy().getLraControls()) {
                if (control.getPath() != null) {
                    controlsByPath.put(control.getPath(), control);
                }
            }
            return controlsByPath;
        }

        return null;
    }
}
