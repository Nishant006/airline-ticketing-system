package com.airline.reservation.repository;

import com.airline.reservation.entity.BookingSeat;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {
  @Query(
      """
                    select s.seatLabel from BookingSeat s
                    where s.flightInstance.schedule.id = :scheduleId and s.flightInstance.flightDate = :date
                      and s.status = com.airline.reservation.entity.BookingSeat$Status.BOOKED
                    """)
  Set<String> findBookedSeats(long scheduleId, LocalDate date);

  // Labels are a 1-2 digit row plus one letter, so shorter labels sort first: 2A before 10A.
  @Query(
      """
                    select s.seatLabel from BookingSeat s where s.booking.id = :bookingId
                    order by length(s.seatLabel), s.seatLabel
                    """)
  List<String> findAssignedSeats(long bookingId);

  @Modifying
  @Query(
      """
                    update BookingSeat s set s.status = com.airline.reservation.entity.BookingSeat$Status.RELEASED
                    where s.booking.id = :bookingId and s.status = com.airline.reservation.entity.BookingSeat$Status.BOOKED
                    """)
  int releaseBookedSeats(long bookingId);
}
