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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.narayana.lra.LRAConstants.*;
import static io.narayana.lra.LRAConstants.STATUS;

@Path("")
@ApplicationScoped
public class SidecarResource {

    @Context
    protected ResourceInfo resourceInfo;

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
    }

    @GET
    @Path("{path:.*}")
    public Response proxyGet(@PathParam("path") String path) {
        String[] parts = path.split("/");
        String lastPath = parts.length > 0 ? parts[parts.length - 1] : path;

        if (!isYamlOK(config)) {
            return Response.status(Response.Status.CONFLICT).entity("Yaml have a problem").build();
        }
        controlsByPath = getControlsByPath();
        if (!controlsByPath.containsKey(lastPath)) {
            return Response.status(Response.Status.NOT_FOUND).entity("Path not found").build();
        }


        LRAControl lraControl = controlsByPath.get(lastPath);
        String lraName = lraControl.getName();
        LRA.Type type = lraControl.getLraSettings() != null ? lraControl.getLraSettings().getType() : null;
        Long timeout = lraControl.getLraSettings() != null ? lraControl.getLraSettings().getTimeLimit() : 0L;
        ChronoUnit timeUnit = lraControl.getLraSettings() != null ? lraControl.getLraSettings().getTimeUnit() : ChronoUnit.SECONDS;
        boolean end = lraControl.getLraSettings() != null && lraControl.getLraSettings().isEnd();

        URI incomingLRA = Current.peek();
        URI activeLRA = null;
        URI recoveryUrl = null;
        niceStringOutput("incomingLRA " + incomingLRA);

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
                            activeLRA = narayanaLRAClient.startLRA(incomingLRA, lraName, timeout, timeUnit);
                        } else {
                            return Response.status(Response.Status.PRECONDITION_FAILED)
                                    .entity("NESTED LRA required but no incoming LRA present")
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
            }


            if (activeLRA != null && hasCompensatorConfig()) {
                String compensatorLink = safeBuildCompensatorURI();

                narayanaLRAClient.enlistCompensator(activeLRA, timeout, compensatorLink, null);
                niceStringOutput("Enlisted compensator for LRA: " + activeLRA);
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

    private boolean hasCompensatorConfig() {
        return config.getLraProxy().getLraControls().stream()
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


    private boolean isYamlOK(LRAProxyConfigFile config) throws RuntimeException {
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

    private URI getFullPathForLraMethod(MethodType methodType) {
        if (methodType == null) {
            throw new IllegalArgumentException("MethodType must not be null");
        }

        String baseUrl = config.getLraProxy().getUrl();
        String serviceName = config.getLraProxy().getServiceName();
        String actionPath = null;

        for (LRAControl control : config.getLraProxy().getLraControls()) {
            if (control.getLraMethod() != null && control.getLraMethod() == methodType) {
                actionPath = control.getPath();
                break;
            }
        }

        if (actionPath == null) {
            throw new IllegalArgumentException("No LRA control found for MethodType: " + methodType);
        }

        String output = String.format("%s/%s/%s", baseUrl, serviceName, actionPath);

        try {
            return new URI(output);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to create URI from: " + output, e);
        }
    }
}
