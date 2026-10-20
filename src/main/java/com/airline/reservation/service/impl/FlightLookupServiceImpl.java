package com.airline.reservation.service.impl;

import com.airline.reservation.entity.FlightSchedule;
import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import com.airline.reservation.model.OperatingFlight;
import com.airline.reservation.model.SeatLayout;
import com.airline.reservation.repository.BookingSeatRepository;
import com.airline.reservation.repository.FlightScheduleRepository;
import com.airline.reservation.service.FlightDatePolicy;
import com.airline.reservation.service.FlightLookupService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class FlightLookupServiceImpl implements FlightLookupService {
  private final FlightScheduleRepository flightScheduleRepository;
  private final BookingSeatRepository bookingSeatRepository;
  private final FlightDatePolicy flightDatePolicy;

  @Override
  public OperatingFlight resolve(String number, LocalDate date) {
    flightDatePolicy.requireInWindow(date);
    FlightSchedule schedule =
        flightScheduleRepository
            .findOperating(number, date.getDayOfWeek())
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.FLIGHT_NOT_FOUND, "Flight does not operate on this date"));
    return new OperatingFlight(
        schedule.getId(),
        schedule.getFlightNumber(),
        schedule.getDepartureTime(),
        new SeatLayout(
            schedule.getAircraft().getRowsCount(), schedule.getAircraft().getSeatLetters()));
  }

  @Override
  public List<String> unavailableSeats(long scheduleId, LocalDate date, List<String> requested) {
    Set<String> booked = bookingSeatRepository.findBookedSeats(scheduleId, date);
    return requested.stream().filter(booked::contains).toList();
  }
}
