package com.airline.reservation.service;

import com.airline.reservation.model.BookingResult;
import com.airline.reservation.model.CreateBookingCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public interface BookingService {
  BookingResult create(@NotNull @Valid CreateBookingCommand command);
}
