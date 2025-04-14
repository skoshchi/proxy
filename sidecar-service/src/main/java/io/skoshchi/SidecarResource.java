package main.java.io.skoshchi;

import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.skoshchi.yaml.LraProxyConfig;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.Yaml;

@Path("")
public class SidecarResource {
    @Inject
    NarayanaLRAClient narayanaLRAClient;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "proxy.config-path")
    private String configPath;

    private LraProxyConfig config;

    private URI parentLRA;

    @PostConstruct
    public void init() {
        config = loadYamlConfig(configPath);
    }

    @GET
    @Path("{path:.*}")
    public Response proxyGet(@PathParam("path") String path, @Context UriInfo uriInfo) {
        String[] parts = path.split("/");

        if (parts.length != 2) {
            return Response.ok("Wrong amount of parameters").build();
        }

        // check the first param == hotel
        String serviceName = config.getLraProxy().getServiceName();
        String serviceUrl = parts[0];
        String pathSuffix = parts[1];
        if (serviceUrl.equals(serviceName)) {
            String methodStartLIRA = config.getLraProxy().getStart().getPath();
            String methodCompleteLIRA = config.getLraProxy().getComplete().getPath();

            if (pathSuffix.equals(methodStartLIRA)) {
                int status = sendGetRequest(pathSuffix, "Method mapped to LRA start is triggered").getStatus();
                if (status == 200) {
                    parentLRA = narayanaLRAClient.startLRA(null, serviceName, null, null);
                    return Response.ok("Lra " + parentLRA + "should be started").build();
                }
            }

            if (pathSuffix.equals(methodCompleteLIRA)) {
                int status = sendGetRequest(pathSuffix, "Method mapped to LRA complete is triggered").getStatus();
                if (status == 200) {
                    narayanaLRAClient.closeLRA(parentLRA);
                    return Response.ok("Lra " + parentLRA + "should be closed").build();
                }
            }
            return sendGetRequest(pathSuffix, "Not mapped method triggered " + config.getLraProxy().getUrl() + "/" + pathSuffix);
        }

        return Response.ok("No such path in yaml").build();
    }

    @GET
    @Path("/demo")
    public Response demoLRAFlow(@Context UriInfo uriInfo) throws URISyntaxException {
        String clientId = "demo";
        narayanaLRAClient.startLRA(null, clientId, 10L, null);

        niceStringOutput("demo run");
        return Response.ok("Demo done").build();
    }


    private void niceStringOutput(String input) {
        System.out.println("===========\n" + input + "\n===========");
    }

    private LraProxyConfig loadYamlConfig(String filePath) {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream(new File(filePath))) {
            return yaml.loadAs(inputStream, LraProxyConfig.class);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("YAML not found: " + filePath, e);
        } catch (IOException e) {
            throw new RuntimeException("Error closing stream for file: " + filePath, e);
        }
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
