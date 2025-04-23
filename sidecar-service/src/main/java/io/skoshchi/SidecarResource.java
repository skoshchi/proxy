package io.skoshchi;

import io.narayana.lra.AnnotationResolver;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.narayana.lra.filter.ServerLRAFilter;
import io.skoshchi.yaml.LRAControl;
import io.skoshchi.yaml.LRAProxyConfigFile;
import io.skoshchi.yaml.MethodType;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.yaml.snakeyaml.Yaml;

@Path("")
public class SidecarResource {
    @Inject
    NarayanaLRAClient narayanaLRAClient;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "proxy.config-path")
    public String configPath;

    @Inject
    Configuration configuration;

    private LRAProxyConfigFile config;
    private Map<String, LRAControl> controlsByPath = new HashMap<>();

    @PostConstruct
    public void init() {
        config = loadYamlConfig(configPath);
        controlsByPath = getControlsByPath();
    }

    @Context
    protected ResourceInfo resourceInfo;

    @GET
    @Path("{path:.*}")
    public Response proxyGet(@PathParam("path") String path, @HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String[] parts = path.split("/");

        if (parts.length != 2) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Expected path format: /{service}/{method}").build();
        }

        String serviceName = parts[0];
        String methodName = parts[1];

        if (!serviceName.equals(config.getLraProxy().getServiceName())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Unknown service: " + serviceName).build();
        }

        LRAControl lraMethod = config.getLraProxy().getLraControls().get(0);
        Method method = resourceInfo.getResourceMethod();
        String lraName = lraMethod.getName();

        LRA.Type type = lraMethod.getLraSettings().getType();
        LRA transactional = AnnotationResolver.resolveAnnotation(LRA.class, method);

        URI newLRA = null;
        Long timeout = lraMethod.getLraSettings().getTimeLimit();

        URI suspendedLRA = null;
        URI incomingLRA = null;
        URI recoveryUrl;
        boolean isLongRunning = false;
        boolean requiresActiveLRA = false;

        boolean end = lraMethod.getLraSettings().isEnd();
        String LRAname = lraMethod.getName();
        ChronoUnit unit = lraMethod.getLraSettings().getTimeUnit();
        String callPath = methodName;
        ArrayList<Progress> progress = null;

        try {
            switch (type) {
                case REQUIRES_NEW:
                    suspendedLRA = incomingLRA;

                    if (progress == null) {
                        progress = new ArrayList<>();
                    }
                    newLRA = lraId = narayanaLRAClient.startLRA(null, lraName, timeout, unit);

                    if (newLRA == null) {
                        // startLRA will have called abortWith on the request context
                        // the failure and any previous actions (the leave request) will be reported via the response filter
                        return Response.ok("Failed to start LRA").build();
                    }
                    break;
                    default:
                        lraId = incomingLRA;
            }

            return sendGetRequest(callPath, "Proxy with LRA: " + lraId);

        } catch (Exception e) {
            return Response.serverError().entity("LRA processing error: " + e.getMessage()).build();
        }
    }


    @GET
    @Path("/demo")
    public Response demoLRAFlow(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String clientId = "demo";


        niceStringOutput("demo run");
        niceStringOutput(config.toString());
        return Response.ok(controlsByPath).build();
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
