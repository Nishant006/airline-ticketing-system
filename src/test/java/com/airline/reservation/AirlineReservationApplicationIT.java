package com.airline.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

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

  @Test
  void contextLoadsAndMigrationsApply() {}
}
