package com.airline.reservation.service;

import com.airline.reservation.model.FlightAvailability;
import com.airline.reservation.model.SeatMap;

import java.time.LocalDate;
import java.util.List;

public interface FlightService {
    List<FlightAvailability> search(String origin, String destination, LocalDate date);

    SeatMap seatMap(String number, LocalDate date);
}
