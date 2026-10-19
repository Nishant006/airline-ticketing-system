package com.airline.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the Spring context starts and every Flyway migration applies cleanly against a real
 * PostgreSQL instance.
 *
 * <p>Named {@code *IT} so it runs under Failsafe ({@code ./mvnw verify}) rather than Surefire
 * ({@code ./mvnw test}), keeping the unit-test run independent of a Docker daemon.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AirlineReservationApplicationIT {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void contextLoadsAndMigrationsApply() {
        assertThat(
                jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM airport").query(Integer.class).single()).isEqualTo(4);
    }
}
