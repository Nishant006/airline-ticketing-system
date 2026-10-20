package com.airline.reservation.service;

import com.airline.reservation.model.BookingResult;
import com.airline.reservation.model.CreateBookingCommand;
import com.airline.reservation.model.OperatingFlight;

public interface BookingTransactionService {
  BookingResult create(OperatingFlight flight, long instanceId, CreateBookingCommand command);
}
