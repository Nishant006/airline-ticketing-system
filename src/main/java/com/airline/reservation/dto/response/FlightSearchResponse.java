package com.airline.reservation.dto.response;

import com.airline.reservation.model.FlightAvailability;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalTime;

public record FlightSearchResponse(
        String flightNumber,
        @JsonFormat(pattern = "HH:mm") LocalTime departureTime,
        @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
        long availableSeats) {
    public static FlightSearchResponse from(FlightAvailability flight) {
        return new FlightSearchResponse(
                flight.flightNumber(),
                flight.departureTime(),
                flight.arrivalTime(),
                flight.availableSeats());
    }
}
