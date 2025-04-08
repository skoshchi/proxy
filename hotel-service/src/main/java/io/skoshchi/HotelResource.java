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
        niceStringOutput("Order status: " + orderStatus);
        return Response.ok(orderStatus).build();
    }

    @GET
    @Path("/start-order")
    public Response startOrder() {
        try {
            Thread.sleep(2000);
            orderStatus = OrderStatus.BOOKING;
            niceStringOutput("Simulates the opening of transaction");
        } catch (InterruptedException e ) {
            e.printStackTrace();
        }
        return Response.ok(orderStatus).build();
    }

    @GET
    @Path("/process-order")
    public Response processOrder() {
        if (ifPaidPass()) {
            return processOrderSuccess();
        } else {
            return processOrderFail();
        }
    }

    // payment can fail
    private boolean ifPaidPass() {
        Random rd = new Random();
        return rd.nextBoolean();
    }

    @GET
    @Path("/process-success")
    public Response processOrderSuccess() {
        try {
            Thread.sleep(2000);
            orderStatus = OrderStatus.BOOKED;
            niceStringOutput("Success order");
        } catch (InterruptedException e ) {
            e.printStackTrace();
        }
        return Response.ok("Success order, LRA goes to complete").build();
    }

    public Response processOrderFail() {
        try {
            Thread.sleep(2000);
            orderStatus = OrderStatus.NOT_BOOKED;
            niceStringOutput("Fail order");
        } catch (InterruptedException e ) {
            e.printStackTrace();
        }
        return Response.ok("Fail order, LRA goes to compensate").build();
    }

    private void niceStringOutput(String input) {
        System.out.println( "===========\n" +
                input + "\n"    +
                "===========\n");
    }
}