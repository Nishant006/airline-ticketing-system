package com.airline.reservation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL container for integration tests.
 *
 * <p>Tests run against the same PostgreSQL major version as production. This is not merely
 * convenient: the double-booking guarantee relies on a <em>partial</em> unique index, which H2 and
 * other embedded engines cannot express. Testing against anything other than PostgreSQL would
 * silently stop testing the actual safety mechanism.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(POSTGRES_IMAGE);
  }
}
