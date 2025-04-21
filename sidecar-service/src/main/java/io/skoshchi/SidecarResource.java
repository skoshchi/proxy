package io.skoshchi;

import io.narayana.lra.AnnotationResolver;
import io.narayana.lra.client.internal.NarayanaLRAClient;
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
import java.util.List;

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

    private URI currentLRAURI;

    @PostConstruct
    public void init() {
        config = loadYamlConfig(configPath);
    }

    @Context
    protected ResourceInfo resourceInfo;

    @GET
    @Path("{path:.*}")
    public Response proxyGet(@PathParam("path") String path) {
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

        LRAControl lraMethods = config.getLraProxy().getLraControls().get(0);
        Method method = resourceInfo.getResourceMethod();
        LRA.Type type = lraMethods.getLraSettings().getType();
        LRA transactional = AnnotationResolver.resolveAnnotation(LRA.class, method);

        // doesn't look good
        URI lraId = currentLRAURI;
        URI newLRA = null;
        Long timeout = lraMethods.getLraSettings().getTimeLimit();

        URI suspendedLRA = null;
        URI incomingLRA = null;
        URI recoveryUrl;
        boolean isLongRunning = false;
        boolean requiresActiveLRA = false;

        boolean end = lraMethods.getLraSettings().isEnd();
        String LRAname = lraMethods.getName();
        ChronoUnit unit = lraMethods.getLraSettings().getTimeUnit();
        String callPath = methodName;

        try {
            switch (type) {
                case REQUIRES_NEW:
                    lraId = narayanaLRAClient.startLRA(null, LRAname, timeout, unit);
                    break;
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

        narayanaLRAClient.startLRA(null, clientId, 10L, null);

        niceStringOutput("demo run");
        niceStringOutput(config.toString());
        return Response.ok("Demo done").build();
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
}
