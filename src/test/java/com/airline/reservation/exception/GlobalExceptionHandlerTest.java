package com.airline.reservation.exception;

import org.hibernate.exception.GenericJDBCException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void temporaryDatabaseFailureDoesNotClaimThatTheWriteFailedOrSucceeded() {
        ResponseEntity<ProblemDetail> response =
                handler.databaseUnavailable(
                        new DataAccessResourceFailureException("Internal JDBC context"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getDetail()).contains("could not be confirmed");
        assertThat(response.getBody().getDetail()).doesNotContain("Internal JDBC context");
    }

    @Test
    void unexpectedFailureDoesNotExposeExceptionContents() {
        ResponseEntity<ProblemDetail> response =
                handler.unexpected(new IllegalStateException("SensitiveFixture"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("Unexpected server error");
    }

    @Test
    void postgresShutdownWrappedByHibernateStillReturns503() {
        JpaSystemException exception =
                new JpaSystemException(
                        new GenericJDBCException(
                                "Internal JDBC context", new SQLException("SensitiveFixture", "57P01")));
        ResponseEntity<ProblemDetail> response = handler.jpaFailure(exception);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getDetail())
                .doesNotContain("SensitiveFixture", "Internal JDBC context");
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
}
