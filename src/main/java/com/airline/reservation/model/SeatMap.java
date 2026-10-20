package com.airline.reservation.model;

import java.time.LocalDate;
import java.util.List;

public record SeatMap(String flightNumber, LocalDate flightDate, List<Seat> seats) {
  public SeatMap {
    seats = List.copyOf(seats);
  }

  public enum Status {
    AVAILABLE,
    BOOKED
  }

  public record Seat(String label, Status status) {}
}
