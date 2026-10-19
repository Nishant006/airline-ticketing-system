package com.airline.reservation.service.impl;

import com.airline.reservation.repository.FlightInstanceRepository;
import com.airline.reservation.service.FlightInstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class FlightInstanceServiceImpl implements FlightInstanceService {
    private final FlightInstanceRepository flightInstanceRepository;

    @Transactional
    @Override
    public long getOrCreate(long scheduleId, LocalDate date) {
        return flightInstanceRepository
                .findId(scheduleId, date)
                .orElseGet(() -> createInstance(scheduleId, date));
    }

    private long createInstance(long scheduleId, LocalDate date) {
        flightInstanceRepository.insertIfAbsent(scheduleId, date);
        // A separate READ COMMITTED statement sees a concurrent inserter's committed row.
        return flightInstanceRepository
                .findId(scheduleId, date)
                .orElseThrow(() -> new IllegalStateException("Materialized instance was not found"));
    }
}
