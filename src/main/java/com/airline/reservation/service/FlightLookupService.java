package com.airline.reservation.service;

import com.airline.reservation.model.OperatingFlight;
import java.time.LocalDate;
import java.util.List;

public interface FlightLookupService {
  OperatingFlight resolve(String number, LocalDate date);

  List<String> unavailableSeats(long scheduleId, LocalDate date, List<String> requested);
}
