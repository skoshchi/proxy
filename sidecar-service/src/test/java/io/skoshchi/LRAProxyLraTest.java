package io.skoshchi;

import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class LRAProxyLraTest {

    @Test
    public void testStartOrder1CreatesNewLRA() {
        Response response = given()
                .when()
                .get("/hotel/start-order-1")
                .then()
                .statusCode(200)
                .header("Long-Running-Action", notNullValue())
                .extract()
                .response();

        String lraUrl = response.getHeader("Long-Running-Action");
        System.out.println("Started LRA: " + lraUrl);

        // Дополнительно можно проверить, что lraUrl начинается с координатора
        assert lraUrl.contains("/lra-coordinator/");
    }

    @Test
    public void testStartOrder2JoinsExistingLRA() {
        // Сначала стартуем новую LRA
        String lraUrl = given()
                .when()
                .get("/hotel/start-order-1")
                .then()
                .statusCode(200)
                .extract()
                .header("Long-Running-Action");

        // Теперь пробуем вызвать /start-order-2 с этим LRA
        given()
                .header("Long-Running-Action", lraUrl)
                .when()
                .get("/hotel/start-order-2")
                .then()
                .statusCode(200); // Должно пройти успешно (REQUIRED)
    }

    @Test
    public void testNestedOrderCreatesNestedLRA() {
        // Сначала стартуем родительскую LRA
        String parentLra = given()
                .when()
                .get("/hotel/start-order-1")
                .then()
                .statusCode(200)
                .extract()
                .header("Long-Running-Action");

        // Теперь стартуем вложенную
        String nestedLra = given()
                .header("Long-Running-Action", parentLra)
                .when()
                .get("/hotel/start-nested-order")
                .then()
                .statusCode(200)
                .extract()
                .header("Long-Running-Action");

        System.out.println("Parent LRA: " + parentLra);
        System.out.println("Nested LRA: " + nestedLra);

        // Nested должна быть другой LRA
        assert nestedLra != null;
        assert !nestedLra.equals(parentLra);
    }

    @Test
    public void testNeverFailsIfLraPresent() {
        // Стартуем внешнюю LRA
        String lraUrl = given()
                .when()
                .get("/hotel/start-order-1")
                .then()
                .statusCode(200)
                .extract()
                .header("Long-Running-Action");

        // Пытаемся вызвать NEVER эндпоинт с активной LRA
        given()
                .header("Long-Running-Action", lraUrl)
                .when()
                .get("/hotel/never-order")
                .then()
                .statusCode(412); // Precondition Failed
    }

    @Test
    public void testMandatoryFailsIfNoLraPresent() {
        // Пробуем вызвать MANDATORY без активной LRA
        given()
                .when()
                .get("/hotel/mandatory-order")
                .then()
                .statusCode(412); // Precondition Failed
    }
}
