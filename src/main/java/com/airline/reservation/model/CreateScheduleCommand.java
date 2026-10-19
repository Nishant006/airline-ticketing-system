package com.airline.reservation.model;

import com.airline.reservation.validation.InputFormats;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public record CreateScheduleCommand(
        @NotNull @Pattern(regexp = InputFormats.FLIGHT_NUMBER) String flightNumber,
        @NotNull @Pattern(regexp = InputFormats.AIRPORT_CODE) String originCode,
        @NotNull @Pattern(regexp = InputFormats.AIRPORT_CODE) String destinationCode,
        @NotNull LocalTime departureTime,
        @NotNull LocalTime arrivalTime,
        @NotBlank @Size(max = 16) String aircraftId,
        @NotEmpty @UniqueElements List<@NotNull DayOfWeek> daysOfOperation) {
    public CreateScheduleCommand {
        daysOfOperation = List.copyOf(daysOfOperation);
    }
}
