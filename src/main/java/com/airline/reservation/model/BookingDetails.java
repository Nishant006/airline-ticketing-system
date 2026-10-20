package com.airline.reservation.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record BookingDetails(
    long id,
    UUID reference,
    String flightNumber,
    LocalDate flightDate,
    LocalTime departureTime,
    BookingStatus status) {}
