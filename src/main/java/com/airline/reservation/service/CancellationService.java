package com.airline.reservation.service;

import com.airline.reservation.model.BookingResult;

import java.util.UUID;

public interface CancellationService {
    BookingResult cancel(UUID reference);
}
