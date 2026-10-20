package com.airline.reservation.service.impl;

import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import com.airline.reservation.exception.SeatConflictException;
import com.airline.reservation.model.BookingResult;
import com.airline.reservation.model.CreateBookingCommand;
import com.airline.reservation.model.OperatingFlight;
import com.airline.reservation.model.SeatLayout;
import com.airline.reservation.service.BookingService;
import com.airline.reservation.service.BookingTransactionService;
import com.airline.reservation.service.FlightDatePolicy;
import com.airline.reservation.service.FlightInstanceService;
import com.airline.reservation.service.FlightLookupService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.NEVER)
public class BookingServiceImpl implements BookingService {
  private final FlightLookupService flightLookupService;
  private final FlightDatePolicy flightDatePolicy;
  private final FlightInstanceService flightInstanceService;
  private final BookingTransactionService bookingTransactionService;

  @Override
  public BookingResult create(CreateBookingCommand request) {
    List<String> requestedSeats =
        request.passengers().stream().map(CreateBookingCommand.Passenger::seat).toList();
    requireDistinctSeats(requestedSeats);
    OperatingFlight flight =
        flightLookupService.resolve(request.flightNumber(), request.flightDate());
    flightDatePolicy.requireBeforeDeparture(request.flightDate(), flight.departureTime());
    requireAircraftSeats(flight.layout(), requestedSeats);
    long instanceId = flightInstanceService.getOrCreate(flight.scheduleId(), request.flightDate());
    BookingResult response = createBooking(flight, instanceId, request, requestedSeats);
    log.info(
        "booking_confirmed reference={} flight={} date={} seats={}",
        response.bookingReference(),
        response.flightNumber(),
        response.flightDate(),
        response.passengerCount());
    return response;
  }

  private void requireDistinctSeats(List<String> requestedSeats) {
    if (requestedSeats.stream().distinct().count() != requestedSeats.size()) {
      throw new BusinessException(ErrorCode.DUPLICATE_SEAT, "Each selected seat must be distinct");
    }
  }

  private void requireAircraftSeats(SeatLayout layout, List<String> requestedSeats) {
    for (String label : requestedSeats) {
      if (!layout.containsSeat(label)) {
        throw new BusinessException(
            ErrorCode.SEAT_NOT_ON_AIRCRAFT,
            "Selected seat does not exist on this aircraft",
            Map.of("seat", label));
      }
    }
  }

  private BookingResult createBooking(
      OperatingFlight flight,
      long instanceId,
      CreateBookingCommand request,
      List<String> requestedSeats) {
    try {
      return bookingTransactionService.create(flight, instanceId, request);
    } catch (SeatConflictException exception) {
      // The transaction proxy has rolled back before querying this fresh snapshot.
      List<String> unavailable =
          flightLookupService.unavailableSeats(
              flight.scheduleId(), request.flightDate(), requestedSeats);
      throw new BusinessException(
          ErrorCode.SEAT_UNAVAILABLE,
          "Selected seats are no longer available. Refresh the seat map before choosing again.",
          Map.of("currentlyUnavailableSeats", unavailable));
    }
  }
}
