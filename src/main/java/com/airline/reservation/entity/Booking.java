package com.airline.reservation.entity;

import com.airline.reservation.model.BookingStatus;
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
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "booking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private UUID reference;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "flight_instance_id")
  private FlightInstance flightInstance;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 9)
  private BookingStatus status;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  public Booking(UUID reference, FlightInstance flightInstance) {
    this.reference = reference;
    this.flightInstance = flightInstance;
    this.status = BookingStatus.CONFIRMED;
  }
}
