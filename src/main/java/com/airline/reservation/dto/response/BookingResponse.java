package com.airline.reservation.dto.response;

import com.airline.reservation.model.BookingResult;
import com.airline.reservation.model.BookingStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
        UUID bookingReference,
        String flightNumber,
        LocalDate flightDate,
        int passengerCount,
        List<String> assignedSeats,
        BookingStatus bookingStatus) {
    public BookingResponse {
        assignedSeats = List.copyOf(assignedSeats);
    }

    public static BookingResponse from(BookingResult booking) {
        return new BookingResponse(
                booking.bookingReference(),
                booking.flightNumber(),
                booking.flightDate(),
                booking.passengerCount(),
                booking.assignedSeats(),
                booking.bookingStatus());
    }
}
