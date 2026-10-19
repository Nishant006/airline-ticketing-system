package com.airline.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseSchemaIT {

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearMutableData() {
        jdbc.sql(
                        """
                                TRUNCATE booking_seat, booking, flight_instance,
                                         flight_schedule_day, flight_schedule RESTART IDENTITY CASCADE
                                """)
                .update();
    }

    @Test
    void airportsHaveAllRequiredFieldsAndStableIataCodes() {
        List<AirportRow> airports =
                jdbc.sql("SELECT iata_code, name, city, country FROM airport ORDER BY iata_code")
                        .query(AirportRow.class)
                        .list();
        assertThat(airports)
                .extracting(AirportRow::iataCode)
                .containsExactly("BOM", "DXB", "LHR", "SIN");
        assertThat(airports)
                .allSatisfy(
                        airport -> {
                            assertThat(airport.name()).isNotBlank();
                            assertThat(airport.city()).isNotBlank();
                            assertThat(airport.country()).isNotBlank();
                        });
    }

    @Test
    void fixedAircraftConfigurationsIncludeTheBriefExampleAndAnotherLayout() {
        List<AircraftRow> aircraft =
                jdbc.sql("SELECT id, type, rows_count, seat_letters FROM aircraft ORDER BY id")
                        .query(AircraftRow.class)
                        .list();
        assertThat(aircraft).hasSize(3);
        assertThat(aircraft)
                .filteredOn(a -> a.id().equals("A320-001"))
                .singleElement()
                .satisfies(
                        a -> {
                            assertThat(a.rowsCount()).isEqualTo(30);
                            assertThat(a.seatLetters()).isEqualTo("ABCDEF");
                        });
        assertThat(aircraft)
                .filteredOn(a -> a.id().equals("AT72-001"))
                .singleElement()
                .satisfies(a -> assertThat(a.seatLetters()).isEqualTo("ABCD"));
        assertThat(aircraft)
                .allSatisfy(
                        a ->
                                assertThat(a.seatLetters().chars().distinct().count())
                                        .isEqualTo(a.seatLetters().length()));
    }

    @Test
    void migrationsCreateTheActualTablesAndRequiredIndexes() {
        assertThat(
                jdbc.sql(
                                """
                                        SELECT tablename FROM pg_tables
                                        WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                                        """)
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder(
                        "airport",
                        "aircraft",
                        "flight_schedule",
                        "flight_schedule_day",
                        "flight_instance",
                        "booking",
                        "booking_seat");
        assertThat(
                jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
                        .query(String.class)
                        .list())
                .contains("uq_booking_seat_active", "uq_instance", "ix_schedule_route", "ix_seat_booking");
        assertThat(
                jdbc.sql(
                                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                        .query(String.class)
                        .list())
                .contains("1", "2");
        assertThat(jdbc.sql("SHOW transaction_isolation").query(String.class).single())
                .isEqualTo("read committed");
    }

    @Test
    void rawSqlRejectsDuplicateActiveSeatButAllowsReleasedSeatToBeRebooked() {
        long instance = instance(LocalDate.of(2027, 1, 4));
        long first = booking(instance);
        long second = booking(instance);
        seat(first, instance, "12A");
        assertThatThrownBy(() -> seat(second, instance, "12A"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("uq_booking_seat_active");
        jdbc.sql("UPDATE booking_seat SET status = 'RELEASED' WHERE booking_id = :booking")
                .param("booking", first)
                .update();
        seat(second, instance, "12A");
        assertThat(
                jdbc.sql("SELECT count(*) FROM booking_seat WHERE status = 'BOOKED'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM booking_seat").query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void sameLabelOnAnotherFlightInstanceIsIndependent() {
        long first = instance(LocalDate.of(2027, 1, 4));
        long second =
                jdbc.sql(
                                """
                                        INSERT INTO flight_instance (schedule_id, flight_date)
                                        SELECT schedule_id, DATE '2027-01-05' FROM flight_instance WHERE id = :id
                                        RETURNING id
                                        """)
                        .param("id", first)
                        .query(Long.class)
                        .single();
        seat(booking(first), first, "12A");
        seat(booking(second), second, "12A");
        assertThat(jdbc.sql("SELECT count(*) FROM booking_seat").query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void compositeForeignKeyRejectsSeatOnAnotherBookingsInstance() {
        long first = instance(LocalDate.of(2027, 1, 4));
        long second =
                jdbc.sql(
                                """
                                        INSERT INTO flight_instance (schedule_id, flight_date)
                                        SELECT schedule_id, DATE '2027-01-05' FROM flight_instance WHERE id = :id
                                        RETURNING id
                                        """)
                        .param("id", first)
                        .query(Long.class)
                        .single();
        long booking = booking(first);
        assertThatThrownBy(() -> seat(booking, second, "12A"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("fk_seat_booking");
    }

    @Test
    void databaseRejectsNoncanonicalLabelsAndDuplicateAircraftLetters() {
        long instance = instance(LocalDate.of(2027, 1, 4));
        long booking = booking(instance);
        assertThatThrownBy(() -> seat(booking, instance, "01A"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_seat_label");
        assertThatThrownBy(
                () ->
                        jdbc.sql(
                                        """
                                                INSERT INTO aircraft (id, type, rows_count, seat_letters)
                                                VALUES ('BAD', 'Invalid', 10, 'AABC')
                                                """)
                                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("ck_aircraft_letters");
    }

    private long instance(LocalDate date) {
        long schedule =
                jdbc.sql(
                                """
                                        INSERT INTO flight_schedule
                                            (flight_number, origin_code, destination_code, departure_time, arrival_time, aircraft_id)
                                        VALUES ('XY101', 'DXB', 'LHR', '09:30', '13:45', 'A320-001')
                                        RETURNING id
                                        """)
                        .query(Long.class)
                        .single();
        return jdbc.sql(
                        """
                                INSERT INTO flight_instance (schedule_id, flight_date)
                                VALUES (:schedule, :date) RETURNING id
                                """)
                .param("schedule", schedule)
                .param("date", date)
                .query(Long.class)
                .single();
    }

    private long booking(long instance) {
        return jdbc.sql(
                        """
                                INSERT INTO booking (reference, flight_instance_id, status)
                                VALUES (:reference, :instance, 'CONFIRMED') RETURNING id
                                """)
                .param("reference", UUID.randomUUID())
                .param("instance", instance)
                .query(Long.class)
                .single();
    }

    private void seat(long booking, long instance, String label) {
        jdbc.sql(
                        """
                                INSERT INTO booking_seat
                                    (booking_id, flight_instance_id, seat_label, passenger_name, status)
                                VALUES (:booking, :instance, :label, '', 'BOOKED')
                                """)
                .param("booking", booking)
                .param("instance", instance)
                .param("label", label)
                .update();
    }

    record AirportRow(String iataCode, String name, String city, String country) {
    }

    record AircraftRow(String id, String type, int rowsCount, String seatLetters) {
    }
}
