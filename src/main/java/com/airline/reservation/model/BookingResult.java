package com.airline.reservation.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BookingResult(
    UUID bookingReference,
    String flightNumber,
    LocalDate flightDate,
    int passengerCount,
    List<String> assignedSeats,
    BookingStatus bookingStatus) {
  public BookingResult {
    assignedSeats = List.copyOf(assignedSeats);
  }
}
