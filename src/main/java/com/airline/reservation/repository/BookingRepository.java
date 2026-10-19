package com.airline.reservation.repository;

import com.airline.reservation.entity.Booking;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @EntityGraph(attributePaths = "flightInstance.schedule")
    Optional<Booking> findByReference(UUID reference);

    @Modifying
    @Query(
            """
                    update Booking b set b.status = com.airline.reservation.model.BookingStatus.CANCELLED,
                        b.cancelledAt = :cancelledAt
                    where b.id = :bookingId and b.status = com.airline.reservation.model.BookingStatus.CONFIRMED
                    """)
    int cancelConfirmed(long bookingId, Instant cancelledAt);
}
