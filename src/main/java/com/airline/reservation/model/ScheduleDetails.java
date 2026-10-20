package com.airline.reservation.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public record ScheduleDetails(
    String flightNumber,
    String originCode,
    String destinationCode,
    LocalTime departureTime,
    LocalTime arrivalTime,
    String aircraftId,
    List<DayOfWeek> daysOfOperation) {
  public ScheduleDetails {
    daysOfOperation = List.copyOf(daysOfOperation);
  }
}
