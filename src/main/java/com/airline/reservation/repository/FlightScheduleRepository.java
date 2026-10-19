package com.airline.reservation.repository;

import com.airline.reservation.entity.FlightSchedule;
import com.airline.reservation.model.FlightAvailability;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FlightScheduleRepository extends JpaRepository<FlightSchedule, Long> {
    @EntityGraph(attributePaths = "aircraft")
    @Query(
            """
                    select s from FlightSchedule s join s.daysOfOperation d
                    where s.flightNumber = :number and d = :day
                    """)
    Optional<FlightSchedule> findOperating(String number, DayOfWeek day);

    @Query(
            """
                    select new com.airline.reservation.model.FlightAvailability(
                        s.flightNumber, s.departureTime, s.arrivalTime,
                        a.rowsCount * length(a.seatLetters) - count(bs.id))
                    from FlightSchedule s join s.aircraft a join s.daysOfOperation d
                    left join FlightInstance fi on fi.schedule = s and fi.flightDate = :date
                    left join BookingSeat bs on bs.flightInstance = fi
                        and bs.status = com.airline.reservation.entity.BookingSeat$Status.BOOKED
                    where s.origin.code = :origin and s.destination.code = :destination and d = :day
                    group by s.id, s.flightNumber, s.departureTime, s.arrivalTime, a.rowsCount, a.seatLetters
                    order by s.departureTime, s.flightNumber
                    """)
    List<FlightAvailability> search(String origin, String destination, LocalDate date, DayOfWeek day);
}
