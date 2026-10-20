package com.airline.reservation;

import org.springframework.boot.SpringApplication;

/**
 * Developer entry point: runs the application against a throwaway PostgreSQL container instead of
 * requiring a locally installed database.
 *
 * <p>Run from the IDE, or {@code ./mvnw spring-boot:test-run}.
 */
public class TestAirlineReservationApplication {

  public static void main(String[] args) {
    SpringApplication.from(AirlineReservationApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
