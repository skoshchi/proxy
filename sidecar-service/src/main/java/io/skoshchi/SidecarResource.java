package main.java.io.skoshchi;

import io.skoshchi.interfece.LraClient;
import io.skoshchi.yaml.LraProxyConfig;
import jakarta.annotation.PostConstruct;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.Yaml;

@Path("")
public class SidecarResource implements LraClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "proxy.config-path")
    private String configPath;

    private LraProxyConfig config;

    @PostConstruct
    public void init() {
        config = loadYamlConfig(configPath);
    }

    @GET
    @Path("{path:.*}")
    public Response proxyGet(@PathParam("path") String path, @Context UriInfo uriInfo) {
        try {
            String targetPath = config.getLraProxy().getUrl() + "/" + path;
            URI targetUri = URI.create(targetPath);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(targetUri)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return Response.ok(response.body()).build();
        } catch (IOException | InterruptedException e) {
            return Response.ok("BAD").build();
        }
    }

    /**
        Sends request to HOTEL_SERVICE_URL + "start-order"
     */
    @Override
    public Response startLRA(URI lraId) {
        niceStringOutput("start LRA");
        return sendGetRequest("**Not done**", "Start LRA works");
    }

    /**
        Sends request to HOTEL_SERVICE_URL + "process-success"
     */
    @Override
    public Response completeLRA(URI lraId) {
        return sendGetRequest("**Not done**", "Complete LRA works");
    }

    /**
        **Not done**
     */
    @Override
    public Response compensateLRA(URI lraId) {
        return Response.ok("**Not done**" + " Compensate LRA works").build();
    }

    @GET
    @Path("/demo")
    public Response demoLRAFlow(URI lraId) {
        niceStringOutput(startLRA(lraId).toString());
        niceStringOutput(compensateLRA(lraId).toString());

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
            String targetUrl = config.getLraProxy().getUrl() + pathSuffix;
            URI targetUri = URI.create(targetUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(targetUri)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return Response.ok(successMessage).build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error during request: " + e.getMessage())
                    .build();
        }
    }
}
