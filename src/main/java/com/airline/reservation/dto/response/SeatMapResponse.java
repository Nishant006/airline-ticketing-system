package com.airline.reservation.dto.response;

import com.airline.reservation.model.SeatMap;
import java.time.LocalDate;
import java.util.List;

public record SeatMapResponse(String flightNumber, LocalDate flightDate, List<Seat> seats) {
  public SeatMapResponse {
    seats = List.copyOf(seats);
  }

  public static SeatMapResponse from(SeatMap map) {
    return new SeatMapResponse(
        map.flightNumber(),
        map.flightDate(),
        map.seats().stream().map(seat -> new Seat(seat.label(), seat.status())).toList());
  }

  public record Seat(String label, SeatMap.Status status) {}
}
