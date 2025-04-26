package io.skoshchi;

import io.narayana.lra.Current;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.skoshchi.yaml.LRAControl;
import io.skoshchi.yaml.LRAProxyConfigFile;
import io.skoshchi.yaml.MethodType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.lra.annotation.AfterLRA;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.yaml.snakeyaml.Yaml;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

@Path("")
@Provider
@ApplicationScoped
public class SidecarResource implements ContainerRequestFilter, ContainerResponseFilter {
    @Inject
    NarayanaLRAClient narayanaLRAClient;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Logger logger = Logger.getLogger(SidecarResource.class.getName());

    // THIS IS BULLSHIT :/
    private final Map<String, URI> activeLRAByClient = new HashMap<>();


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

    @GET
    @Path("{path:.*}")
    public Response proxyGet(@PathParam("path") String path,
                             @Context ContainerRequestContext requestContext) {
        String[] parts = path.split("/");
        String lastPath = parts.length > 0 ? parts[parts.length - 1] : path;

        if (!controlsByPath.containsKey(lastPath)) {
            return Response.status(Response.Status.NOT_FOUND).entity("Path not found").build();
        }

        LRAControl lraControl = controlsByPath.get(lastPath);
        LRA.Type type = lraControl.getLraSettings().getType();
        Long timeout = lraControl.getLraSettings().getTimeLimit();
        ChronoUnit timeUnit = lraControl.getLraSettings().getTimeUnit();
        String lraName = lraControl.getName();

        URI incomingLRA = null;
        URI activeLRA = null;

        try {
            String rawLRAHeader = requestContext.getHeaderString(LRA_HTTP_CONTEXT_HEADER);
            if (rawLRAHeader != null && !rawLRAHeader.isEmpty()) {
                incomingLRA = URI.create(rawLRAHeader);
            }

            switch (type) {
                case REQUIRES_NEW:
                    activeLRA = narayanaLRAClient.startLRA(null, lraName, timeout, timeUnit);
                    niceStringOutput("Started new REQUIRES_NEW LRA: " + activeLRA);
                    break;
                case REQUIRED:
                    if (incomingLRA != null) {
                        activeLRA = incomingLRA;
                        niceStringOutput("Using incoming REQUIRED LRA: " + activeLRA);
                    } else {
                        activeLRA = narayanaLRAClient.startLRA(null, lraName, timeout, timeUnit);
                        niceStringOutput("Started new REQUIRED LRA: " + activeLRA);
                    }
                    break;
                default:
                    activeLRA = incomingLRA;
                    break;
            }

            if (activeLRA != null) {
                Current.push(activeLRA);
                // Запоминаем LRA в свойства запроса, чтобы ответный фильтр его подхватил
                requestContext.setProperty(LRA_HTTP_CONTEXT_HEADER, activeLRA.toASCIIString());
            }

            return sendGetRequest(lastPath, "Proxy work done");

        } catch (Exception e) {
            return Response.serverError().entity("LRA error: " + e.getMessage()).build();
        }
    }

    @GET
    @Path("/demo")
    public Response demoLRAFlow(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId) {
        String test = "hotel/status-order";
        String[] parts = test.split("/");
        String lastPath = parts.length > 0 ? parts[parts.length - 1] : test;

        niceStringOutput("demo run");
        niceStringOutput(config.toString());
        return Response.ok(lastPath).build();
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
                    throw new RuntimeException(prefix + "lraSettings.type' must not be null");
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

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        List<String> lraHeaders = requestContext.getHeaders().get(LRA_HTTP_CONTEXT_HEADER);

        if (lraHeaders != null && !lraHeaders.isEmpty()) {
            try {
                URI lraId = new URI(lraHeaders.get(0));
                logger.info("[LRA-Filter] Incoming LRA: " + lraId);
                Current.push(lraId);
            } catch (Exception e) {
                logger.warning("[LRA-Filter] Failed to parse LRA header: " + lraHeaders);
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        Object activeLRA = requestContext.getProperty(LRA_HTTP_CONTEXT_HEADER);

        if (activeLRA != null) {
            responseContext.getHeaders().putSingle(LRA_HTTP_CONTEXT_HEADER, activeLRA.toString());
            niceStringOutput("Outgoing LRA Header set: " + activeLRA);
        } else {
            niceStringOutput("No LRA context to propagate.");
        }

        Current.pop(); // Чистим стек в конце обработки
    }


//    @Path("/after-lra")
//    @GET
//    @AfterLRA
//    public Response afterLRA() {
//        return Response.ok().build();
//    }
}
