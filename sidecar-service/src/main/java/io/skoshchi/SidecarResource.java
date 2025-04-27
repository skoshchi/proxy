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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_PARENT_CONTEXT_HEADER;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.Type.NESTED;

@Path("")
@ApplicationScoped
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

    @GET
    @Path("{path:.*}")
    public Response proxyGet(@PathParam("path") String path) {
        String[] parts = path.split("/");
        String lastPath = parts.length > 0 ? parts[parts.length - 1] : path;

        if (!controlsByPath.containsKey(lastPath)) {
            return Response.status(Response.Status.NOT_FOUND).entity("Path not found").build();
        }

        List<Object> fullContext = Current.getContexts();

        LRAControl lraControl = controlsByPath.get(lastPath);
        String lraName = lraControl.getName();
        LRA.Type type = lraControl.getLraSettings().getType();
        Long timeout = lraControl.getLraSettings().getTimeLimit();
        ChronoUnit timeUnit = lraControl.getLraSettings().getTimeUnit();
        boolean end = lraControl.getLraSettings().isEnd();

        URI incomingLRA = Current.peek();
        URI activeLRA = null;
        niceStringOutput("incomingLRA " + incomingLRA);

        try {
            switch (type) {
                case NESTED:
                    if (incomingLRA != null) {
                        activeLRA = narayanaLRAClient.startLRA(incomingLRA, lraName, timeout, timeUnit);
                    } else {
                        return Response.status(Response.Status.PRECONDITION_FAILED)
                                .entity("NESTED LRA requested but no incoming parent LRA present")
                                .build();
                    }
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

                case REQUIRES_NEW:
                    activeLRA = narayanaLRAClient.startLRA(null, lraName, timeout, timeUnit);
                    niceStringOutput("Started REQUIRES_NEW LRA: " + activeLRA);
                    break;
                default:
                    niceStringOutput("No LRA started (type = " + type + ")");
            }

            Response proxyResponse = sendGetRequest(lastPath, "Proxy with LRA: " + activeLRA);

            if (end && activeLRA != null) {
                narayanaLRAClient.closeLRA(activeLRA);
                niceStringOutput("Closed LRA: " + activeLRA);
            }

            Current.push(activeLRA);
            Current.addActiveLRACache(activeLRA);
            return proxyResponse;

        } catch (Exception e) {
            return Response.serverError().entity("Error processing LRA: " + e.getMessage()).build();
        }
    }

    private void niceStringOutput(String input) {
        System.out.println("===========\n" + input + "\n===========");
    }

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
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Request error: " + e.getMessage()).build();
        }
    }

    private LRAProxyConfigFile loadYamlConfig(String filePath) {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream(new File(filePath))) {
            return yaml.loadAs(inputStream, LRAProxyConfigFile.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load YAML: " + filePath, e);
        }
    }

    private Map<String, LRAControl> getControlsByPath() {
        Map<String, LRAControl> controlsByPath = new HashMap<>();
        if (config != null && config.getLraProxy() != null && config.getLraProxy().getLraControls() != null) {
            for (LRAControl control : config.getLraProxy().getLraControls()) {
                if (control.getPath() != null) {
                    controlsByPath.put(control.getPath(), control);
                }
            }
        }
        return controlsByPath;
    }
}
