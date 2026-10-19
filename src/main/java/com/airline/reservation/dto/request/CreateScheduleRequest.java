package com.airline.reservation.dto.request;

import com.airline.reservation.model.CreateScheduleCommand;
import com.airline.reservation.validation.InputFormats;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public record CreateScheduleRequest(
        @NotNull @Pattern(regexp = InputFormats.FLIGHT_NUMBER) String flightNumber,
        @NotNull @Pattern(regexp = InputFormats.AIRPORT_CODE) String originCode,
        @NotNull @Pattern(regexp = InputFormats.AIRPORT_CODE) String destinationCode,
        @NotNull @JsonFormat(pattern = "HH:mm", lenient = OptBoolean.FALSE) LocalTime departureTime,
        @NotNull @JsonFormat(pattern = "HH:mm", lenient = OptBoolean.FALSE) LocalTime arrivalTime,
        @NotBlank @Size(max = 16) String aircraftId,
        @NotEmpty @UniqueElements List<@NotNull DayOfWeek> daysOfOperation) {
    public CreateScheduleCommand toCommand() {
        return new CreateScheduleCommand(
                flightNumber,
                originCode,
                destinationCode,
                departureTime,
                arrivalTime,
                aircraftId,
                daysOfOperation);
    }
}
