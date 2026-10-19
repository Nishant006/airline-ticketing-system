package com.airline.reservation.entity;

import com.airline.reservation.config.DayOfWeekConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

@Entity
@Table(name = "flight_schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FlightSchedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_number", nullable = false, unique = true, length = 8)
    private String flightNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "origin_code")
    private Airport origin;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_code")
    private Airport destination;

    @Column(name = "departure_time", nullable = false)
    private LocalTime departureTime;

    @Column(name = "arrival_time", nullable = false)
    private LocalTime arrivalTime;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aircraft_id")
    private Aircraft aircraft;

    @ElementCollection
    @CollectionTable(name = "flight_schedule_day", joinColumns = @JoinColumn(name = "schedule_id"))
    @Column(name = "day_of_week", nullable = false)
    @Convert(converter = DayOfWeekConverter.class)
    @Getter(AccessLevel.NONE)
    private Set<DayOfWeek> daysOfOperation;

    @Builder
    public FlightSchedule(
            String flightNumber,
            Airport origin,
            Airport destination,
            LocalTime departureTime,
            LocalTime arrivalTime,
            Aircraft aircraft,
            Set<DayOfWeek> daysOfOperation) {
        this.flightNumber = flightNumber;
        this.origin = origin;
        this.destination = destination;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.aircraft = aircraft;
        this.daysOfOperation = Set.copyOf(daysOfOperation);
    }

    public Set<DayOfWeek> getDaysOfOperation() {
        return Set.copyOf(daysOfOperation);
    }
}
