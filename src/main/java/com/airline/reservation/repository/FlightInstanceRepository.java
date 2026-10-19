package com.airline.reservation.repository;

import com.airline.reservation.entity.FlightInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface FlightInstanceRepository extends JpaRepository<FlightInstance, Long> {
    @Query(
            "select i.id from FlightInstance i where i.schedule.id = :scheduleId and i.flightDate = :date")
    Optional<Long> findId(long scheduleId, LocalDate date);

    @Modifying
    @Query(
            value =
                    """
                            INSERT INTO flight_instance (schedule_id, flight_date) VALUES (:scheduleId, :date)
                            ON CONFLICT ON CONSTRAINT uq_instance DO NOTHING
                            """,
            nativeQuery = true)
    int insertIfAbsent(long scheduleId, LocalDate date);
}
