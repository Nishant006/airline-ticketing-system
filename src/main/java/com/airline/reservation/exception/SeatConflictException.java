package com.airline.reservation.exception;

public class SeatConflictException extends RuntimeException {
  public SeatConflictException(Throwable cause) {
    super("An active seat allocation already exists", cause);
  }
}
