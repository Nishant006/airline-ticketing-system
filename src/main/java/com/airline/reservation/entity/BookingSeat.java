package com.airline.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "booking_seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingSeat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flight_instance_id")
    private FlightInstance flightInstance;

    @Column(name = "seat_label", nullable = false, length = 3)
    private String seatLabel;

    @Column(name = "passenger_name", nullable = false, columnDefinition = "text")
    private String passengerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Status status;

    public BookingSeat(Booking booking, String seatLabel, String passengerName) {
        this.booking = booking;
        this.flightInstance = booking.getFlightInstance();
        this.seatLabel = seatLabel;
        this.passengerName = passengerName;
        this.status = Status.BOOKED;
    }

    public enum Status {
        BOOKED,
        RELEASED
    }
}
