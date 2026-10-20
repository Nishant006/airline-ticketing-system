package com.airline.reservation.model;

import com.airline.reservation.validation.InputFormats;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;

public record CreateBookingCommand(
    @NotNull @Pattern(regexp = InputFormats.FLIGHT_NUMBER) String flightNumber,
    @NotNull LocalDate flightDate,
    @NotEmpty List<@NotNull @Valid Passenger> passengers) {
  public CreateBookingCommand {
    passengers = List.copyOf(passengers);
  }

  public record Passenger(
      @NotNull String name, @NotNull @Pattern(regexp = InputFormats.SEAT_LABEL) String seat) {}
}
