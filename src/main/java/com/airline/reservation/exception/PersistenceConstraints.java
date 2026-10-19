package com.airline.reservation.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

public final class PersistenceConstraints {
    private PersistenceConstraints() {
    }

    public static boolean isUniqueViolationOf(
            DataIntegrityViolationException exception, String name) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "23505".equals(violation.getSQLState())
                        && name.equals(violation.getConstraintName());
            }
        }
        return false;
    }
}
