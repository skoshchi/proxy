package io.skoshchi;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.util.Random;

@Path("/hotel")
public class HotelResource {


    private OrderStatus orderStatus = OrderStatus.NOT_BOOKED;

    @GET
    @Path("/get-order-status")
    public Response getOrderStatus() {
        niceStringOutput("[get-order-status] Order status: " + orderStatus);
        return Response.ok(orderStatus).build();
    }

    @GET
    @Path("/start-order-1")
    public Response startOrder1() throws InterruptedException {
        Thread.sleep(1000);
        orderStatus = OrderStatus.BOOKING;
        niceStringOutput("[start-order] Booking started");
        return Response.ok("Order started").build();
    }

    @GET
    @Path("/start-order-2")
    public Response startOrder2() throws InterruptedException {
        Thread.sleep(1000);
        orderStatus = OrderStatus.BOOKING;
        niceStringOutput("[start-order] Booking started");
        return Response.ok("Order started").build();
    }

    @GET
    @Path("/process-success")
    public Response processOrderSuccess() throws InterruptedException {
        Thread.sleep(1000);
        orderStatus = OrderStatus.BOOKED;
        niceStringOutput("[process-success] Order booked");
        return Response.ok("Success").build();
    }

    @POST
    @Path("/compensate")
    public Response compensate() {
        orderStatus = OrderStatus.NOT_BOOKED;
        niceStringOutput("[Compensate] called by sidecar");
        return Response.ok("Compensated").build();
    }

    @POST
    @Path("/complete")
    public Response complete() {
        niceStringOutput("[Complete] called by sidecar");
        return Response.ok("Completed").build();
    }

    @GET
    @Path("/status")
    public Response status() {
        niceStringOutput("[Status] Order status: " + orderStatus);
        return Response.ok(orderStatus.name()).build();
    }

    @POST
    @Path("/forget")
    public Response forget() {
        niceStringOutput("[Forget] Cleanup complete");
        return Response.ok("Forgotten").build();
    }

    @GET
    @Path("/leave")
    public Response leaveOrder() {
        niceStringOutput("[Leave] called by sidecar");
        return Response.ok("Leave").build();
    }

    @GET
    @Path("/after-lra")
    public Response afterLRA() {
        niceStringOutput("[AfterLRA] Final callback received");
        return Response.ok("AfterLRA handled").build();
    }

    @GET
    @Path("/start-nested-order")
    public Response startNestedOrder() throws InterruptedException {
        Thread.sleep(500);
        orderStatus = OrderStatus.BOOKING;
        niceStringOutput("[start-nested-order] Booking started inside nested LRA");
        return Response.ok("Nested order started").build();
    }

    @GET
    @Path("/never-order")
    public Response neverOrder() {
        niceStringOutput("[never-order] This should never run inside an LRA");
        return Response.ok("Never LRA operation completed").build();
    }

    @GET
    @Path("/mandatory-order")
    public Response mandatoryOrder() {
        niceStringOutput("[mandatory-order] This MUST be called inside an LRA");
        return Response.ok("Mandatory LRA operation completed").build();
    }

    @GET
    @Path("/supports-order")
    public Response supportsOrder() {
        niceStringOutput("[supports-order] Running with or without LRA context (SUPPORTS)");
        return Response.ok("Supports LRA operation completed").build();
    }

    @GET
    @Path("/not-supported-order")
    public Response notSupportedOrder() {
        niceStringOutput("[not-supported-order] Running outside any LRA (NOT_SUPPORTED)");
        return Response.ok("Not Supported LRA operation completed").build();
    }

    private void niceStringOutput(String input) {
        System.out.println( "===========\n" +
                input + "\n"    +
                "===========\n");
    }
}