package com.airline.reservation.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.GenericJDBCException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void temporaryDatabaseFailureDoesNotClaimThatTheWriteFailedOrSucceeded(CapturedOutput output) {
    ResponseEntity<ProblemDetail> response =
        handler.databaseUnavailable(
            new DataAccessResourceFailureException("Internal JDBC context"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(response.getBody().getDetail()).contains("could not be confirmed");
    assertThat(response.getBody().getDetail()).doesNotContain("Internal JDBC context");
    assertThat(output.getAll())
        .contains("database_operation_unconfirmed", "sqlState=-", "constraint=-");
    assertThat(output.getAll()).doesNotContain("Internal JDBC context");
  }

  @Test
  void unexpectedFailureDoesNotExposeExceptionContents(CapturedOutput output) {
    ResponseEntity<ProblemDetail> response =
        handler.unexpected(new IllegalStateException("SensitiveFixture"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().getDetail()).isEqualTo("Unexpected server error");
    assertThat(output.getAll())
        .contains(
            "rootType=java.lang.IllegalStateException",
            "location="
                + getClass().getName()
                + ".unexpectedFailureDoesNotExposeExceptionContents:");
    assertThat(output.getAll()).doesNotContain("SensitiveFixture");
  }

  @Test
  void serviceContractViolationIs400WithoutEchoingTheViolation(CapturedOutput output) {
    ResponseEntity<ProblemDetail> response =
        handler.invalidMethodInput(
            new jakarta.validation.ConstraintViolationException("SensitiveFixture", Set.of()));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().getProperties())
        .containsOnly(entry("errorCode", "VALIDATION_FAILED"));
    assertThat(response.getBody().getDetail()).isEqualTo("Invalid request fields");
    assertThat(output.getAll())
        .contains("request_rejected status=400 type=ConstraintViolationException");
    assertThat(output.getAll()).doesNotContain("SensitiveFixture");
  }

  @Test
  void postgresShutdownWrappedByHibernateStillReturns503(CapturedOutput output) {
    JpaSystemException exception =
        new JpaSystemException(
            new GenericJDBCException(
                "Internal JDBC context", new SQLException("SensitiveFixture", "57P01")));
    ResponseEntity<ProblemDetail> response = handler.jpaFailure(exception);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(response.getBody().getDetail())
        .doesNotContain("SensitiveFixture", "Internal JDBC context");
    assertThat(output.getAll()).contains("rootType=java.sql.SQLException", "sqlState=57P01");
    assertThat(output.getAll()).doesNotContain("SensitiveFixture", "Internal JDBC context");
  }

  @Test
  void unclassifiedHibernateFailureRemainsAnExplicit500() {
    JpaSystemException exception =
        new JpaSystemException(
            new GenericJDBCException(
                "Internal JDBC context", new SQLException("SensitiveFixture", "XX000")));
    ResponseEntity<ProblemDetail> response = handler.jpaFailure(exception);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().getDetail()).isEqualTo("Unexpected server error");
  }

  @Test
  void unexpectedDatabaseFailureRetainsMetadataWithoutSqlOrPassengerValues(CapturedOutput output) {
    SQLException cause = new SQLException("SensitiveFixture", "23505");
    cause.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              "com.airline.reservation.service.impl.BookingTransactionServiceImpl",
              "persist",
              "BookingTransactionServiceImpl.java",
              61)
        });
    ConstraintViolationException violation =
        new ConstraintViolationException(
            "Internal JDBC context",
            cause,
            "insert into booking_seat (passenger_name) values ('SensitiveFixture')",
            "uq_booking_seat_active");
    ResponseEntity<ProblemDetail> response =
        handler.unexpected(new DataIntegrityViolationException("Sensitive wrapper", violation));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().getProperties()).containsOnlyKeys("errorCode");
    assertThat(output.getAll())
        .contains(
            "type=org.springframework.dao.DataIntegrityViolationException",
            "rootType=java.sql.SQLException",
            "sqlState=23505",
            "constraint=uq_booking_seat_active",
            "location=com.airline.reservation.service.impl.BookingTransactionServiceImpl.persist:61");
    assertThat(output.getAll())
        .doesNotContain(
            "SensitiveFixture", "Internal JDBC context", "Sensitive wrapper", "insert into");
  }

  @Test
  void invalidDatabaseMetadataIsNotWrittenToLogs(CapturedOutput output) {
    ConstraintViolationException violation =
        new ConstraintViolationException(
            "Sensitive exception",
            new SQLException("Sensitive passenger", "23505\nSensitive state"),
            "Sensitive SQL",
            "uq_seat\nSensitive constraint");
    handler.unexpected(violation);
    assertThat(output.getAll()).contains("sqlState=-", "constraint=-");
    assertThat(output.getAll()).doesNotContain("Sensitive");
  }

  @Test
  void cyclicCausesDoNotHangDiagnosticsOrExposeMessages(CapturedOutput output) {
    IllegalStateException first = new IllegalStateException("Sensitive first");
    RuntimeException second = new RuntimeException("Sensitive second", first);
    first.initCause(second);
    ResponseEntity<ProblemDetail> response =
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> handler.unexpected(first));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(output.getAll()).contains("rootType=java.lang.RuntimeException");
    assertThat(output.getAll()).doesNotContain("Sensitive");
  }
}
