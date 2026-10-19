package com.airline.reservation;

import com.airline.reservation.service.FlightInstanceService;
import com.airline.reservation.service.FlightLookupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class InstanceMaterializationIT extends IntegrationSupport {
    @Autowired
    private FlightInstanceService instances;
    @Autowired
    private FlightLookupService flights;
    @Autowired
    private DataSource dataSource;

    @Test
    void concurrentFirstMaterializationReturnsOneCommittedId() throws Exception {
        schedule("XY101", "A320-001");
        long scheduleId = flights.resolve("XY101", TOMORROW).scheduleId();
        List<Long> ids = concurrently(24, ignored -> instances.getOrCreate(scheduleId, TOMORROW));
        assertThat(ids.stream().distinct()).hasSize(1);
        assertThat(count("flight_instance")).isEqualTo(1);
    }

    @Test
    void existingInstanceNeedsOnlyLookupAndNoWrite() {
        schedule("XY101", "A320-001");
        long scheduleId = flights.resolve("XY101", TOMORROW).scheduleId();
        long existing = instances.getOrCreate(scheduleId, TOMORROW);
        statistics().clear();
        assertThat(instances.getOrCreate(scheduleId, TOMORROW)).isEqualTo(existing);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void separateStatementSeesRowCommittedAfterTheInitialLookup() throws Exception {
        schedule("XY101", "A320-001");
        long scheduleId = flights.resolve("XY101", TOMORROW).scheduleId();
        try (Connection connection = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     "INSERT INTO flight_instance (schedule_id, flight_date) VALUES (?, ?) RETURNING id")) {
                    statement.setLong(1, scheduleId);
                    statement.setObject(2, TOMORROW);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        id = result.getLong(1);
                    }
                }
                Future<Long> future = executor.submit(() -> instances.getOrCreate(scheduleId, TOMORROW));
                await()
                        .atMost(Duration.ofSeconds(10))
                        .untilAsserted(
                                () ->
                                        assertThat(
                                                jdbc.sql(
                                                                """
                                                                        SELECT count(*) FROM pg_stat_activity
                                                                        WHERE wait_event_type = 'Lock'
                                                                          AND upper(query) LIKE '%ON CONFLICT ON CONSTRAINT UQ_INSTANCE%'
                                                                        """)
                                                        .query(Integer.class)
                                                        .single())
                                                .isPositive());
                connection.commit();
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(id);
            } finally {
                connection.rollback();
            }
        }
    }
}
