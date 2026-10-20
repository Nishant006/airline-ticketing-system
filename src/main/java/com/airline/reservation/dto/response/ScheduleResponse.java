package com.airline.reservation.dto.response;

import com.airline.reservation.model.ScheduleDetails;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public record ScheduleResponse(
    String flightNumber,
    String originCode,
    String destinationCode,
    @JsonFormat(pattern = "HH:mm") LocalTime departureTime,
    @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
    String aircraftId,
    List<DayOfWeek> daysOfOperation) {

  public ScheduleResponse {
    daysOfOperation = List.copyOf(daysOfOperation);
  }

  public static ScheduleResponse from(ScheduleDetails schedule) {
    return new ScheduleResponse(
        schedule.flightNumber(),
        schedule.originCode(),
        schedule.destinationCode(),
        schedule.departureTime(),
        schedule.arrivalTime(),
        schedule.aircraftId(),
        schedule.daysOfOperation());
  }
}
