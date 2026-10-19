package com.airline.reservation;

import com.airline.reservation.model.CreateScheduleCommand;
import com.airline.reservation.service.ScheduleService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

@SpringBootTest(
        properties = {
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.jpa.properties.hibernate.session.events.log=false"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
public abstract class IntegrationSupport {
    protected static final LocalDate TODAY = LocalDate.of(2027, 3, 1);
    protected static final LocalDate TOMORROW = TODAY.plusDays(1);

    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected ScheduleService schedules;
    @Autowired
    protected MockMvc mvc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    protected Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void resetMutableData() {
        jdbc.sql(
                        """
                                TRUNCATE booking_seat, booking, flight_instance,
                                         flight_schedule_day, flight_schedule RESTART IDENTITY CASCADE
                                """)
                .update();
    }

    protected void schedule(String number, String aircraft) {
        schedules.create(
                new CreateScheduleCommand(
                        number,
                        "DXB",
                        "LHR",
                        LocalTime.of(9, 30),
                        LocalTime.of(13, 45),
                        aircraft,
                        List.of(DayOfWeek.values())));
    }

    protected long instance(String number, LocalDate date) {
        return jdbc.sql(
                        """
                                INSERT INTO flight_instance (schedule_id, flight_date)
                                SELECT id, :date FROM flight_schedule WHERE flight_number = :number
                                RETURNING id
                                """)
                .param("date", date)
                .param("number", number)
                .query(Long.class)
                .single();
    }

    protected void rawBooking(long instance, List<String> labels) {
        long booking =
                jdbc.sql(
                                """
                                        INSERT INTO booking (reference, flight_instance_id, status)
                                        VALUES (:reference, :instance, 'CONFIRMED') RETURNING id
                                        """)
                        .param("reference", UUID.randomUUID())
                        .param("instance", instance)
                        .query(Long.class)
                        .single();
        for (String label : labels) {
            jdbc.sql(
                            """
                                    INSERT INTO booking_seat
                                        (booking_id, flight_instance_id, seat_label, passenger_name, status)
                                    VALUES (:booking, :instance, :label, 'Fixture', 'BOOKED')
                                    """)
                    .param("booking", booking)
                    .param("instance", instance)
                    .param("label", label)
                    .update();
        }
    }

    protected int count(String table) {
        if (!List.of(
                        "flight_schedule", "flight_schedule_day", "flight_instance", "booking", "booking_seat")
                .contains(table)) {
            throw new IllegalArgumentException("Unknown fixture table");
        }
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    protected <T> List<T> concurrently(int count, IntFunction<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("Concurrent start timed out");
                                    }
                                    return action.apply(index);
                                }));
            }
            boolean allReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            if (!allReady) {
                throw new IllegalStateException("Concurrent workers did not become ready");
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
