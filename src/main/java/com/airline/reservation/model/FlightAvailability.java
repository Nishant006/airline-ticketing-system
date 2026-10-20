package com.airline.reservation.model;

import java.time.LocalTime;

public record FlightAvailability(
    String flightNumber, LocalTime departureTime, LocalTime arrivalTime, long availableSeats) {}
