package com.airline.reservation.dto.request;

import com.airline.reservation.model.CreateBookingCommand;
import com.airline.reservation.validation.InputFormats;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;

public record CreateBookingRequest(
    @NotNull @Pattern(regexp = InputFormats.FLIGHT_NUMBER) String flightNumber,
    @NotNull LocalDate flightDate,
    @NotEmpty List<@NotNull @Valid Passenger> passengers) {

  public CreateBookingCommand toCommand() {
    return new CreateBookingCommand(
        flightNumber,
        flightDate,
        passengers.stream()
            .map(
                passenger -> new CreateBookingCommand.Passenger(passenger.name(), passenger.seat()))
            .toList());
  }

  public record Passenger(
      @NotNull String name, @NotNull @Pattern(regexp = InputFormats.SEAT_LABEL) String seat) {}
}
