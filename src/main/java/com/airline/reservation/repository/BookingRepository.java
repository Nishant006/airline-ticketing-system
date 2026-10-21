package com.airline.reservation.repository;

import com.airline.reservation.entity.Booking;
import com.airline.reservation.model.BookingDetails;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, Long> {
  @Query(
      """
                    select new com.airline.reservation.model.BookingDetails(
                        b.id, b.reference, s.flightNumber, i.flightDate, s.departureTime, b.status)
                    from Booking b join b.flightInstance i join i.schedule s
                    where b.reference = :reference
                    """)
  Optional<BookingDetails> findDetailsByReference(UUID reference);

  @Modifying
  @Query(
      """
                    update Booking b set b.status = com.airline.reservation.model.BookingStatus.CANCELLED,
                        b.cancelledAt = :cancelledAt
                    where b.id = :bookingId and b.status = com.airline.reservation.model.BookingStatus.CONFIRMED
                    """)
  int cancelConfirmed(long bookingId, Instant cancelledAt);
}
