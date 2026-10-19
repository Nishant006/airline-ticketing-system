package com.airline.reservation.service.impl;

import com.airline.reservation.entity.Booking;
import com.airline.reservation.entity.BookingSeat;
import com.airline.reservation.exception.PersistenceConstraints;
import com.airline.reservation.exception.SeatConflictException;
import com.airline.reservation.model.BookingResult;
import com.airline.reservation.model.BookingStatus;
import com.airline.reservation.model.CreateBookingCommand;
import com.airline.reservation.model.OperatingFlight;
import com.airline.reservation.repository.BookingRepository;
import com.airline.reservation.repository.BookingSeatRepository;
import com.airline.reservation.repository.FlightInstanceRepository;
import com.airline.reservation.service.BookingTransactionService;
import com.airline.reservation.service.FlightDatePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingTransactionServiceImpl implements BookingTransactionService {
    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final FlightInstanceRepository flightInstanceRepository;
    private final FlightDatePolicy flightDatePolicy;

    @Transactional
    @Override
    public BookingResult create(
            OperatingFlight flight, long instanceId, CreateBookingCommand request) {
        flightDatePolicy.requireInWindow(request.flightDate());
        flightDatePolicy.requireBeforeDeparture(request.flightDate(), flight.departureTime());
        List<CreateBookingCommand.Passenger> ordered =
                request.passengers().stream()
                        .sorted(Comparator.comparing(CreateBookingCommand.Passenger::seat))
                        .toList();
        UUID reference = UUID.randomUUID();
        persist(reference, instanceId, ordered);
        return new BookingResult(
                reference,
                flight.flightNumber(),
                request.flightDate(),
                ordered.size(),
                ordered.stream().map(CreateBookingCommand.Passenger::seat).toList(),
                BookingStatus.CONFIRMED);
    }

    private void persist(
            UUID reference, long instanceId, List<CreateBookingCommand.Passenger> passengers) {
        try {
            Booking booking =
                    bookingRepository.save(
                            new Booking(reference, flightInstanceRepository.getReferenceById(instanceId)));
            bookingSeatRepository.saveAllAndFlush(
                    passengers.stream()
                            .map(passenger -> new BookingSeat(booking, passenger.seat(), passenger.name()))
                            .toList());
        } catch (DataIntegrityViolationException exception) {
            if (PersistenceConstraints.isUniqueViolationOf(exception, "uq_booking_seat_active")) {
                throw new SeatConflictException(exception);
            }
            throw exception;
        }
    }
}
