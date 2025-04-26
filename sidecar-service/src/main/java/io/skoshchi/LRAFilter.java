package io.skoshchi;

import io.narayana.lra.Current;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;

import java.io.IOException;
import java.net.URI;
import java.util.logging.Logger;

import static org.eclipse.microprofile.lra.annotation.ws.rs.LRA.LRA_HTTP_CONTEXT_HEADER;

@Provider
public class LRAFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final Logger log = Logger.getLogger(LRAFilter.class.getName());

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String lraHeader = requestContext.getHeaderString(LRA_HTTP_CONTEXT_HEADER);
        if (lraHeader != null && !lraHeader.isEmpty()) {
            try {
                URI lraId = URI.create(lraHeader);
                Current.push(lraId);
                log.info("[LRAFilter] Incoming LRA pushed: " + lraId);
            } catch (Exception e) {
                log.warning("[LRAFilter] Invalid LRA header: " + lraHeader);
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        URI currentLRA = Current.peek();
        if (currentLRA != null) {
            responseContext.getHeaders().putSingle(LRA_HTTP_CONTEXT_HEADER, currentLRA.toASCIIString());
            log.info("[LRAFilter] Setting outgoing LRA header: " + currentLRA);
            Current.pop();
            log.info("[LRAFilter] Popped LRA: " + currentLRA);
        }
    }
}
