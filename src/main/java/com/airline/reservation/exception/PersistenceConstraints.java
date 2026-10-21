package com.airline.reservation.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public final class PersistenceConstraints {
  public static final String UQ_SCHEDULE_FLIGHT_NUMBER = "uq_schedule_flight_number";
  public static final String UQ_BOOKING_SEAT_ACTIVE = "uq_booking_seat_active";

  private PersistenceConstraints() {}

  public static boolean isUniqueViolationOf(
      DataIntegrityViolationException exception, String name) {
    for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE
            && name.equals(violation.getConstraintName());
      }
    }
    return false;
  }
}
