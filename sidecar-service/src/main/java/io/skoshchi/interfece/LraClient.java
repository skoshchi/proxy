package io.skoshchi.interfece;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.lra.annotation.Compensate;
import org.eclipse.microprofile.lra.annotation.Complete;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.net.URI;

@Path("/sidecar")
@RegisterRestClient(configKey = "lra-api")
public interface LraClient {

    @LRA(LRA.Type.REQUIRES_NEW)
    @Path("/start-lra")
    @PUT
    Response startLRA(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId);

    @Compensate
    @Path("/compensate-lra")
    @PUT
    Response compensateLRA(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId);

    @Complete
    @Path("/complete-lra")
    @PUT
    Response completeLRA(@HeaderParam(LRA.LRA_HTTP_CONTEXT_HEADER) URI lraId);
}
