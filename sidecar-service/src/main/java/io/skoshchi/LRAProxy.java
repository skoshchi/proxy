package io.skoshchi;

import io.narayana.lra.Current;
import io.narayana.lra.client.internal.NarayanaLRAClient;
import io.skoshchi.yaml.LRAProxyConfigFile;
import io.skoshchi.yaml.MethodType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static io.narayana.lra.LRAConstants.*;
import static io.narayana.lra.LRAConstants.STATUS;
import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

@Path("")
@ApplicationScoped
public class LRAProxy {

    private String LRA_HTTP_HEADER = "Long-Running-Action";
    public static final String STATUS_CODE_QUERY_NAME = "Coerce-Status";


    private static final Logger log = Logger.getLogger(LRAProxy.class.getName());

    @Context
    protected ResourceInfo resourceInfo;

    @Inject
    NarayanaLRAClient narayanaLRAClient;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "proxy.config-path")
    public String configPath;

    private LRAProxyConfigFile config;
    private Map<String, io.skoshchi.yaml.LRAProxy> controlsByPath = new HashMap<>();

    @PostConstruct
    public void init() {
        config = loadYamlConfig(configPath);
        if (!isYamlOK(config)) {
            throw new IllegalStateException("YAML configuration is invalid: " + configPath);
        }
        controlsByPath = getControlsByPath();
    }

    @GET
    @Path("{path:.*}")
    public Response proxyGet(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                             @PathParam("path") String path) {
        return handleRequest("GET", lraId, path, 200);
    }

    @POST
    @Path("{path:.*}")
    public Response proxyPost(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                              @PathParam("path") String path) {
        return handleRequest("POST", lraId, path, 200);
    }

    @PUT
    @Path("{path:.*}")
    public Response proxyPut(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                             @DefaultValue("200") @QueryParam(STATUS_CODE_QUERY_NAME) int coerceStatus,
                             @PathParam("path") String path) {
        return handleRequest("PUT", lraId, path, coerceStatus);
    }

    @DELETE
    @Path("{path:.*}")
    public Response proxyDelete(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                                @PathParam("path") String path) {
        return handleRequest("DELETE", lraId, path, 200);
    }

    @PATCH
    @Path("{path:.*}")
    public Response proxyPatch(@HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId,
                               @PathParam("path") String path) {
        return handleRequest("PATCH", lraId, path, 200);
    }

    public Response handleRequest(String httpMethod, @HeaderParam(LRA_HTTP_CONTEXT_HEADER) URI lraId, String path, int coerceStatus) {
        log.info("[handleRequest] " + httpMethod +
                " Incoming path: " + path +
                " Header lraId: " + lraId);

        String[] parts = path.split("/");

        if (!parts[0].equals(config.getProxy().getService())) {
            throw new IllegalStateException("Service name is invalid: " + configPath);
        }

        String lastPath = parts.length > 1
                ? "/" + String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length)): "/";

        if (!controlsByPath.containsKey(lastPath)) {
            throw new IllegalStateException("No path " + lastPath +" in yaml");
        }

        Method method = resourceInfo.getResourceMethod();
        String clientId = method.getDeclaringClass().getName() + "#" + method.getName();
        io.skoshchi.yaml.LRAProxy lraProxy = controlsByPath.get(lastPath);
        LRA.Type type = lraProxy.getLraSettings() != null ? lraProxy.getLraSettings().getType() : null;
        Long timeout = lraProxy.getLraSettings() != null ? lraProxy.getLraSettings().getTimeLimit() : 0L;
        ChronoUnit timeUnit = lraProxy.getLraSettings() != null ? lraProxy.getLraSettings().getTimeUnit() : ChronoUnit.SECONDS;
        boolean end = lraProxy.getLraSettings() != null && lraProxy.getLraSettings().isEnd();

        URI incomingLRA = Current.peek();
        URI activeLRA = null;
        URI recoveryUrl = null;
        log.info("incomingLRA " + incomingLRA);
        log.info("Incoming path" + path);
        try {
            if (type != null) {
                switch (type) {
                    case SUPPORTS:
                        if (incomingLRA != null) {
                            activeLRA = incomingLRA;
                        } else {
                            activeLRA = null;
                        }
                        break;

                    case NOT_SUPPORTED:
                        activeLRA = null;
                        break;

                    case NEVER:
                        if (incomingLRA != null) {
                            return Response.status(Response.Status.PRECONDITION_FAILED)
                                    .entity("[NEVER] LRA is not required but incoming LRA present")
                                    .header(LRA_HTTP_HEADER, incomingLRA != null ? incomingLRA.toASCIIString() : "")
                                    .build();
                        } else {
                            activeLRA = null;
                        }
                        break;

                    case MANDATORY:
                        if (incomingLRA != null) {
                            activeLRA = incomingLRA;
                        } else {
                            return Response.status(Response.Status.PRECONDITION_FAILED)
                                    .entity("[MANDATORY] LRA required but no incoming LRA present")
                                    .build();
                        }
                        break;

                    case NESTED:
                        if (incomingLRA != null) {
                            activeLRA = narayanaLRAClient.startLRA(incomingLRA, clientId, timeout, timeUnit);
                        } else {
                            return Response.status(Response.Status.PRECONDITION_FAILED)
                                    .entity("NESTED LRA required but no incoming LRA present")
                                    .build();
                        }
                        break;

                    case REQUIRED:
                        if (incomingLRA != null) {
                            activeLRA = incomingLRA;
                            log.info("Using incoming REQUIRED LRA: " + activeLRA);
                        } else {
                            activeLRA = narayanaLRAClient.startLRA(null, clientId, timeout, timeUnit);
                            log.info("Started new REQUIRED LRA: " + activeLRA);
                        }
                        break;

                    case REQUIRES_NEW:
                        activeLRA = narayanaLRAClient.startLRA(null, clientId, timeout, timeUnit);
                        log.info("Started REQUIRES_NEW LRA: " + activeLRA);
                        break;

                    default:
                        log.info("No LRA started (type = " + type + ")");
                }
            }

            if (activeLRA != null && hasCompensatorConfig()) {
                String compensatorLink = safeBuildCompensatorURI();

                System.out.println("compensatorLink = " + compensatorLink);
                narayanaLRAClient.enlistCompensator(activeLRA, timeout, compensatorLink, null);
                log.info("Enlisted compensator for LRA: " + activeLRA);
            }




            Response response;
            if (httpMethod.equals("GET")) {
                response = sendGetRequest(lastPath, activeLRA, activeLRA.toString());
            } else {
                response = sendRequest(httpMethod, lastPath, activeLRA, activeLRA.toString(), coerceStatus);
            }

            if (end && activeLRA != null) {
                narayanaLRAClient.cancelLRA(activeLRA);

                log.info("Closed LRA: " + activeLRA);
            }
            Current.push(activeLRA);
            Current.addActiveLRACache(activeLRA);

            return response;
        } catch (Exception e) {
            log.severe("Exception in handleRequest: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .header("X-Error-Message", e.getMessage())
                    .entity("Proxy error occurred")
                    .build();
        }
    }

    private boolean hasCompensatorConfig() {
        return config.getProxy().getLra().stream()
                .anyMatch(control -> control.getLraMethod() == MethodType.COMPENSATE
                        || control.getLraMethod() == MethodType.AFTER_LRA);
    }

    private String safeBuildCompensatorURI() {
         StringBuilder linkHeaderValue = new StringBuilder();

        appendLinkIfExists(linkHeaderValue, COMPENSATE, getFullPathForLraMethodSafe(MethodType.COMPENSATE));
        appendLinkIfExists(linkHeaderValue, COMPLETE, getFullPathForLraMethodSafe(MethodType.COMPLETE));
        appendLinkIfExists(linkHeaderValue, FORGET, getFullPathForLraMethodSafe(MethodType.FORGET));
        appendLinkIfExists(linkHeaderValue, LEAVE, getFullPathForLraMethodSafe(MethodType.LEAVE));
        appendLinkIfExists(linkHeaderValue, AFTER, getFullPathForLraMethodSafe(MethodType.AFTER_LRA));
        appendLinkIfExists(linkHeaderValue, STATUS, getFullPathForLraMethodSafe(MethodType.STATUS));

        return linkHeaderValue.toString();
    }

    private void appendLinkIfExists(StringBuilder builder, String key, URI uri) {
        if (uri != null) {
            makeLink(builder, key, uri);
        }
    }

    private URI getFullPathForLraMethodSafe(MethodType methodType) {
        try {
            return getFullPathForLraMethod(methodType);
        } catch (Exception e) {
            return null;
        }
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

    private Response sendGetRequest(String pathSuffix, URI activeLRA, String successMessage) {
        try {
            String targetUrl = config.getProxy().getUrl() + "/" + config.getProxy().getService() + pathSuffix;
            URI targetUri = URI.create(targetUrl);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(targetUri)
                    .GET();

            if (activeLRA != null) {
                builder.header(LRA_HTTP_CONTEXT_HEADER, activeLRA.toASCIIString());
            }

            HttpRequest request = builder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[sendGetRequest] Response: " + response.body());
            return Response.ok(successMessage).build();
        } catch (IllegalArgumentException e) {
            log.severe("[sendGetRequest] Invalid URI: " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid URI in request: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            log.severe("[sendGetRequest] Exception: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .header("X-Error-Message", e.getMessage())
                    .entity("Proxy error occurred")
                    .build();
        }
    }

    private Response sendRequest(String httpMethod, String pathSuffix, URI activeLRA, String successMessage, int coerceStatus) {
        try {
            String baseUrl = config.getProxy().getUrl() + "/" + config.getProxy().getService() + pathSuffix;
            if ("PUT".equalsIgnoreCase(httpMethod)) {
                String separator = baseUrl.contains("?") ? "&" : "?";
                baseUrl += separator + STATUS_CODE_QUERY_NAME + "=" + coerceStatus;
            }


            URI targetUri = URI.create(baseUrl);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(targetUri);

            switch (httpMethod.toUpperCase()) {
                case "POST":
                    builder.POST(HttpRequest.BodyPublishers.noBody());
                    break;
                case "PUT":
                    builder.PUT(HttpRequest.BodyPublishers.noBody());
                    break;
                case "DELETE":
                    builder.DELETE();
                    break;
                case "PATCH":
                    builder.method("PATCH", HttpRequest.BodyPublishers.noBody());
                    break;
                default:
                    builder.GET();
            }

            if (activeLRA != null) {
                builder.header(LRA_HTTP_CONTEXT_HEADER, activeLRA.toASCIIString());
            }

            HttpRequest request = builder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[sendRequest] Response: " + response.body());
            return Response.ok(successMessage).build();
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

    private LRAProxyConfigFile loadYamlConfig(String filePath) {
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream(new File(filePath))) {
            return yaml.loadAs(inputStream, LRAProxyConfigFile.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load YAML: " + filePath, e);
        }
    }


    private boolean isYamlOK(LRAProxyConfigFile config) throws RuntimeException {
        List<io.skoshchi.yaml.LRAProxy> controls = config.getProxy().getLra();
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
                                MethodType.AFTER_LRA)
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

    private Map<String, io.skoshchi.yaml.LRAProxy> getControlsByPath() {
        Map<String, io.skoshchi.yaml.LRAProxy> controlsByPath = new HashMap<>();
        if (config != null && config.getProxy() != null && config.getProxy().getLra() != null) {
            for (io.skoshchi.yaml.LRAProxy control : config.getProxy().getLra()) {
                if (control.getPath() != null) {
                    controlsByPath.put(control.getPath(), control);
                }
            }
        }
        return controlsByPath;
    }

    private URI getFullPathForLraMethod(MethodType methodType) {
        if (methodType == null) {
            throw new IllegalArgumentException("MethodType must not be null");
        }

        String baseUrl = config.getProxy().getUrl();
        String serviceName = config.getProxy().getService();
        String actionPath = null;

        for (io.skoshchi.yaml.LRAProxy control : config.getProxy().getLra()) {
            if (control.getLraMethod() != null && control.getLraMethod() == methodType) {
                actionPath = control.getPath().replaceAll("/", "");;
                break;
            }
        }

        if (actionPath == null) {
            throw new IllegalArgumentException("No LRA control found for MethodType: " + methodType);
        }

        String output = String.format("%s/%s/%s", baseUrl, "tcktests/" + serviceName, actionPath);

        try {
            return new URI(output);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to create URI from: " + output, e);
        }
    }
}
