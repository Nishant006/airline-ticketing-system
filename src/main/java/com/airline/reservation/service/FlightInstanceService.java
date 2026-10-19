package com.airline.reservation.service;

import java.time.LocalDate;

public interface FlightInstanceService {
    long getOrCreate(long scheduleId, LocalDate date);
}
