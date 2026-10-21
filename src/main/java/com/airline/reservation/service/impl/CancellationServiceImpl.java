package com.airline.reservation.service.impl;

import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import com.airline.reservation.model.BookingDetails;
import com.airline.reservation.model.BookingResult;
import com.airline.reservation.model.BookingStatus;
import com.airline.reservation.repository.BookingRepository;
import com.airline.reservation.repository.BookingSeatRepository;
import com.airline.reservation.service.CancellationService;
import com.airline.reservation.service.FlightDatePolicy;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CancellationServiceImpl implements CancellationService {
  private final BookingRepository bookingRepository;
  private final BookingSeatRepository bookingSeatRepository;
  private final FlightDatePolicy flightDatePolicy;
  private final Clock clock;

  @Transactional
  @Override
  public BookingResult cancel(UUID reference) {
    BookingDetails booking = findBooking(reference);
    requireCancellable(booking);
    List<String> assignedSeats = bookingSeatRepository.findAssignedSeats(booking.id());
    if (bookingRepository.cancelConfirmed(booking.id(), clock.instant()) != 1) {
      throw alreadyCancelled();
    }
    releaseAllSeats(booking.id(), assignedSeats.size());
    return new BookingResult(
        reference,
        booking.flightNumber(),
        booking.flightDate(),
        assignedSeats.size(),
        assignedSeats,
        BookingStatus.CANCELLED);
  }

  private BookingDetails findBooking(UUID reference) {
    return bookingRepository
        .findDetailsByReference(reference)
        .orElseThrow(
            () -> new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "Booking does not exist"));
  }

  private void requireCancellable(BookingDetails booking) {
    if (booking.status() == BookingStatus.CANCELLED) {
      throw alreadyCancelled();
    }
    flightDatePolicy.requireBeforeDeparture(booking.flightDate(), booking.departureTime());
  }

  private void releaseAllSeats(long bookingId, int expectedCount) {
    int releasedCount = bookingSeatRepository.releaseBookedSeats(bookingId);
    if (expectedCount == 0 || releasedCount != expectedCount) {
      throw new IllegalStateException("Booking allocations do not match cancellation release");
    }
  }

  private BusinessException alreadyCancelled() {
    return new BusinessException(ErrorCode.ALREADY_CANCELLED, "Booking is already cancelled");
  }
}
