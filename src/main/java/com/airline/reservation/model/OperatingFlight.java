package com.airline.reservation.model;

import java.time.LocalTime;

public record OperatingFlight(
        long scheduleId, String flightNumber, LocalTime departureTime, SeatLayout layout) {
}
