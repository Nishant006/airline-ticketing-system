package com.airline.reservation.exception;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.JDBCException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private final SQLExceptionTranslator sqlExceptionTranslator =
      new SQLExceptionSubclassTranslator();

  private static ProblemDetail problem(HttpStatusCode status, ErrorCode code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(
        URI.create("urn:airline:error:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
    problem.setProperty("errorCode", code.name());
    return problem;
  }

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ProblemDetail> business(BusinessException exception) {
    HttpStatus status =
        switch (exception.getCode()) {
          case VALIDATION_FAILED, SAME_ORIGIN_DESTINATION, DUPLICATE_SEAT -> HttpStatus.BAD_REQUEST;
          case FLIGHT_NOT_FOUND, BOOKING_NOT_FOUND, REQUEST_NOT_FOUND -> HttpStatus.NOT_FOUND;
          case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
          case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
          case NOT_ACCEPTABLE -> HttpStatus.NOT_ACCEPTABLE;
          case UNKNOWN_REFERENCE, DATE_OUT_OF_WINDOW, SEAT_NOT_ON_AIRCRAFT ->
              HttpStatus.UNPROCESSABLE_CONTENT;
          case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
          case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
          case DUPLICATE_SCHEDULE, SEAT_UNAVAILABLE, ALREADY_CANCELLED, FLIGHT_DEPARTED ->
              HttpStatus.CONFLICT;
        };
    ProblemDetail problem = problem(status, exception.getCode(), exception.getMessage());
    exception.getDetails().forEach(problem::setProperty);
    log.info("request_rejected status={} code={}", status.value(), exception.getCode());
    return ResponseEntity.status(status).body(problem);
  }

  @ExceptionHandler({
    TransientDataAccessException.class,
    DataAccessResourceFailureException.class,
    TransactionException.class
  })
  ResponseEntity<ProblemDetail> databaseUnavailable(RuntimeException exception) {
    logFailure("database_operation_unconfirmed", exception);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.SERVICE_UNAVAILABLE,
                "The database operation could not be confirmed. A write outcome may be unknown; do not blindly retry writes."));
  }

  @ExceptionHandler(JpaSystemException.class)
  ResponseEntity<ProblemDetail> jpaFailure(JpaSystemException exception) {
    if (exception.getCause() instanceof JDBCException jdbcException) {
      SQLException cause = jdbcException.getSQLException();
      DataAccessException translated =
          sqlExceptionTranslator.translate("Database operation", null, cause);
      if (translated instanceof TransientDataAccessException
          || translated instanceof DataAccessResourceFailureException) {
        return databaseUnavailable(exception);
      }
    }
    return unexpected(exception);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception exception) {
    logFailure("unexpected_request_failure", exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                "Unexpected server error"));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> invalidMethodInput(ConstraintViolationException exception) {
    log.info("request_rejected status=400 type={}", exception.getClass().getSimpleName());
    return ResponseEntity.badRequest()
        .body(
            problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Invalid request fields"));
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    HttpStatus httpStatus = HttpStatus.valueOf(status.value());
    ErrorCode code =
        switch (httpStatus) {
          case NOT_FOUND -> ErrorCode.REQUEST_NOT_FOUND;
          case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
          case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
          case NOT_ACCEPTABLE -> ErrorCode.NOT_ACCEPTABLE;
          default ->
              httpStatus.is5xxServerError()
                  ? ErrorCode.INTERNAL_ERROR
                  : ErrorCode.VALIDATION_FAILED;
        };
    ProblemDetail problem = problem(status, code, httpStatus.getReasonPhrase());
    if (exception instanceof MethodArgumentNotValidException validation) {
      problem.setProperty(
          "errors",
          validation.getBindingResult().getFieldErrors().stream()
              .map(
                  error ->
                      Map.of(
                          "field",
                          error.getField(),
                          "message",
                          String.valueOf(error.getDefaultMessage())))
              .toList());
    }
    log.info(
        "request_rejected status={} type={}", status.value(), exception.getClass().getSimpleName());
    return super.handleExceptionInternal(exception, problem, headers, status, request);
  }

  private void logFailure(String event, Exception exception) {
    FailureDiagnostics diagnostics = FailureDiagnostics.from(exception);
    log.error(
        "{} type={} rootType={} sqlState={} constraint={} location={}",
        event,
        exception.getClass().getName(),
        diagnostics.rootType(),
        diagnostics.sqlState(),
        diagnostics.constraint(),
        diagnostics.location());
  }

  private record FailureDiagnostics(
      String rootType, String sqlState, String constraint, String location) {
    private static FailureDiagnostics from(Throwable exception) {
      Throwable root = exception;
      String sqlState = "-";
      String constraint = "-";
      String location = "-";
      Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
      for (Throwable cause = exception;
          cause != null && visited.add(cause);
          cause = cause.getCause()) {
        root = cause;
        if (cause instanceof SQLException sqlException) {
          sqlState = metadata(sqlException.getSQLState(), "[A-Z0-9]{5}");
        }
        if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
          constraint = metadata(violation.getConstraintName(), "[A-Za-z_][A-Za-z0-9_]{0,62}");
        }
        location =
            Arrays.stream(cause.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.airline.reservation."))
                .map(
                    frame ->
                        frame.getClassName()
                            + "."
                            + frame.getMethodName()
                            + ":"
                            + frame.getLineNumber())
                .findFirst()
                .orElse(location);
      }
      return new FailureDiagnostics(root.getClass().getName(), sqlState, constraint, location);
    }

    private static String metadata(String value, String pattern) {
      return value != null && value.matches(pattern) ? value : "-";
    }
  }
}
