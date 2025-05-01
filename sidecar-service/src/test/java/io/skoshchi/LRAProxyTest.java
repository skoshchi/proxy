package io.skoshchi;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LRAProxyTest {

    @BeforeEach
    public void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = 8081;
    }

    @Test
    public void testStartOrder1() {
        given()
                .when()
                .get("/hotel/start-order-1")
                .then()
                .statusCode(Status.OK.getStatusCode())
                .body(containsString("Proxy with LRA"));
    }

    @Test
    public void testStartOrder2() {
        given()
                .when()
                .get("/hotel/start-order-2")
                .then()
                .statusCode(Status.OK.getStatusCode())
                .body(containsString("Proxy with LRA"));
    }

    @Test
    public void testUnknownPathReturns404() {
        given()
                .when()
                .get("/hotel/unknown-path")
                .then()
                .statusCode(Status.NOT_FOUND.getStatusCode())
                .body(containsString("Path not found"));
    }
}