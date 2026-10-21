package com.airline.reservation.service.impl;

import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import com.airline.reservation.model.FlightAvailability;
import com.airline.reservation.model.OperatingFlight;
import com.airline.reservation.model.SeatMap;
import com.airline.reservation.repository.BookingSeatRepository;
import com.airline.reservation.repository.FlightScheduleRepository;
import com.airline.reservation.service.FlightDatePolicy;
import com.airline.reservation.service.FlightLookupService;
import com.airline.reservation.service.FlightService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FlightServiceImpl implements FlightService {
  private final FlightScheduleRepository flightScheduleRepository;
  private final BookingSeatRepository bookingSeatRepository;
  private final FlightDatePolicy flightDatePolicy;
  private final FlightLookupService flightLookupService;

  @Override
  public List<FlightAvailability> search(String origin, String destination, LocalDate date) {
    flightDatePolicy.requireInWindow(date);
    if (origin.equals(destination)) {
      throw new BusinessException(
          ErrorCode.SAME_ORIGIN_DESTINATION, "Origin and destination must differ");
    }
    return flightScheduleRepository.search(origin, destination, date, date.getDayOfWeek()).stream()
        .filter(flight -> !flightDatePolicy.hasDeparted(date, flight.departureTime()))
        .toList();
  }

  @Override
  public SeatMap seatMap(String number, LocalDate date) {
    OperatingFlight flight = flightLookupService.resolve(number, date);
    Set<String> booked = bookingSeatRepository.findBookedSeats(flight.scheduleId(), date);
    return new SeatMap(
        number,
        date,
        flight.layout().seatLabels().stream()
            .map(
                label ->
                    new SeatMap.Seat(
                        label,
                        booked.contains(label) ? SeatMap.Status.BOOKED : SeatMap.Status.AVAILABLE))
            .toList());
  }
}
