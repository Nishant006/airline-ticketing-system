package com.airline.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CrossInstanceIT {
    private static final String SCHEDULE =
            """
                    {"flightNumber":"XY101","originCode":"DXB","destinationCode":"LHR",
                     "departureTime":"09:30","arrivalTime":"13:45","aircraftId":"A320-001",
                     "daysOfOperation":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"]}
                    """;
    private static final String BOOKING =
            """
                    {"flightNumber":"XY101","flightDate":"2027-03-02",
                     "passengers":[{"name":"Synthetic","seat":"1A"}]}
                    """;

    @Test
    void independentContextsShareSeatOwnershipAndSeparateReadinessFromLiveness() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            try (ConfigurableApplicationContext first = application(postgres, "first");
                 ConfigurableApplicationContext second = application(postgres, "second");
                 HttpClient client =
                         HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                URI firstUri = baseUri(first);
                URI secondUri = baseUri(second);
                assertThat(send(client, firstUri, "/actuator/health/readiness", null).statusCode())
                        .isEqualTo(200);
                assertThat(send(client, firstUri, "/api/v1/admin/flight-schedules", SCHEDULE).statusCode())
                        .isEqualTo(201);
                List<HttpResponse<String>> results = race(client, firstUri, secondUri);
                assertThat(results.stream().map(HttpResponse::statusCode).toList())
                        .containsExactlyInAnyOrder(201, 409);
                JdbcClient jdbc = first.getBean(JdbcClient.class);
                assertThat(jdbc.sql("SELECT count(*) FROM booking").query(Integer.class).single())
                        .isEqualTo(1);
                assertThat(
                        jdbc.sql("SELECT count(*) FROM booking_seat WHERE status='BOOKED'")
                                .query(Integer.class)
                                .single())
                        .isEqualTo(1);
                String reference =
                        jdbc.sql("SELECT reference::text FROM booking").query(String.class).single();
                assertThat(
                        send(client, secondUri, "/api/v1/bookings/" + reference + "/cancel", "{}")
                                .statusCode())
                        .isEqualTo(200);
                assertThat(send(client, firstUri, "/api/v1/bookings", BOOKING).statusCode()).isEqualTo(201);
                postgres.stop();
                assertThat(send(client, firstUri, "/actuator/health/liveness", null).statusCode())
                        .isEqualTo(200);
                assertThat(send(client, firstUri, "/actuator/health/readiness", null).statusCode())
                        .isEqualTo(503);
                HttpResponse<String> unavailable = send(client, firstUri, "/api/v1/bookings", BOOKING);
                assertThat(unavailable.statusCode()).isEqualTo(503);
                assertThat(unavailable.body()).contains("SERVICE_UNAVAILABLE");
            }
        }
    }

    private ConfigurableApplicationContext application(PostgreSQLContainer postgres, String name) {
        return new SpringApplicationBuilder(
                AirlineReservationApplication.class, FixedClockConfiguration.class)
                .run(
                        "--server.port=0",
                        "--airline.instance-id=" + name,
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.datasource.hikari.maximum-pool-size=5",
                        "--spring.datasource.hikari.connection-timeout=500",
                        "--springdoc.api-docs.enabled=false",
                        "--springdoc.swagger-ui.enabled=false");
    }

    private URI baseUri(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://localhost:" + port);
    }

    private List<HttpResponse<String>> race(HttpClient client, URI first, URI second)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<HttpResponse<String>>> actions =
                List.of(
                        () -> bookAtStart(client, first, ready, start),
                        () -> bookAtStart(client, second, ready, start));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> one = executor.submit(actions.getFirst());
            Future<HttpResponse<String>> two = executor.submit(actions.getLast());
            boolean allReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(allReady).isTrue();
            return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
    }

    private HttpResponse<String> bookAtStart(
            HttpClient client, URI uri, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent start timed out");
        }
        return send(client, uri, "/api/v1/bookings", BOOKING);
    }

    private HttpResponse<String> send(HttpClient client, URI base, String path, String body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15));
        if (body == null) {
            request.GET();
        } else {
            request
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
