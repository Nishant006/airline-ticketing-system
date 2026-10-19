package com.airline.reservation;

import com.airline.reservation.model.BookingStatus;
import com.airline.reservation.model.CreateBookingCommand;
import com.airline.reservation.service.BookingService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReadIsolationIT extends IntegrationSupport {
    @Autowired
    @Qualifier("readDataSource")
    private HikariDataSource readPool;

    @Autowired
    @Qualifier("writeDataSource")
    private HikariDataSource writePool;

    @Autowired
    private BookingService bookings;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void prepareFlight() throws Exception {
        schedule("XY101", "A320-001");
        searchSuccessfully();
    }

    @Test
    void exhaustedReadsFailPromptlyWithoutBlockingBookingsAndRecover() throws Exception {
        List<Connection> connections = borrowAll(readPool);
        try {
            long started = System.nanoTime();
            mvc.perform(
                            get("/api/v1/flights")
                                    .param("originCode", "DXB")
                                    .param("destinationCode", "LHR")
                                    .param("date", TOMORROW.toString()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            mvc.perform(get("/api/v1/flights/XY101/seat-map").param("date", TOMORROW.toString()))
                    .andExpect(status().isServiceUnavailable());
            assertThat(
                    bookings
                            .create(
                                    new CreateBookingCommand(
                                            "XY101",
                                            TOMORROW,
                                            List.of(new CreateBookingCommand.Passenger("Passenger", "1A"))))
                            .bookingStatus())
                    .isEqualTo(BookingStatus.CONFIRMED);
        } finally {
            closeAll(connections);
        }
        searchSuccessfully();
    }

    @Test
    void exhaustedWritesDoNotBlockSearchOrSeatMaps() throws Exception {
        List<Connection> connections = borrowAll(writePool);
        try {
            searchSuccessfully();
            mvc.perform(get("/api/v1/flights/XY101/seat-map").param("date", TOMORROW.toString()))
                    .andExpect(status().isOk());
        } finally {
            closeAll(connections);
        }
    }

    @Test
    void tableLockProducesPrompt503AndReadPoolRecovers() throws Exception {
        try (Connection connection = writePool.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("LOCK TABLE flight_schedule IN ACCESS EXCLUSIVE MODE");
                long started = System.nanoTime();
                mvc.perform(
                                get("/api/v1/flights")
                                        .param("originCode", "DXB")
                                        .param("destinationCode", "LHR")
                                        .param("date", TOMORROW.toString()))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            } finally {
                connection.rollback();
            }
        }
        searchSuccessfully();
    }

    @Test
    void statementDeadlineCancelsDatabaseWorkAndRollsBackBeforeReuse() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        List<Connection> held = borrowAll(readPool);
        try {
            int backendId;
            try (Connection available = held.removeLast();
                 Statement statement = available.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(rows.next()).isTrue();
                backendId = rows.getInt(1);
            }
            long started = System.nanoTime();
            assertThatThrownBy(
                    () ->
                            transaction.executeWithoutResult(
                                    ignored -> jdbc.sql("SELECT pg_sleep(2)").query(String.class).single()))
                    .isInstanceOfSatisfying(
                            QueryTimeoutException.class,
                            exception ->
                                    assertThat(exception.getMostSpecificCause())
                                            .isInstanceOfSatisfying(
                                                    SQLException.class,
                                                    cause -> assertThat(cause.getSQLState()).isEqualTo("57014")));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            Integer reusedBackend =
                    transaction.execute(
                            ignored -> jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
            assertThat(reusedBackend).isEqualTo(backendId);
            searchSuccessfully();
        } finally {
            closeAll(held);
        }
    }

    private void searchSuccessfully() throws Exception {
        mvc.perform(
                        get("/api/v1/flights")
                                .param("originCode", "DXB")
                                .param("destinationCode", "LHR")
                                .param("date", TOMORROW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flightNumber").value("XY101"));
    }

    private List<Connection> borrowAll(HikariDataSource pool) throws SQLException {
        List<Connection> connections = new ArrayList<>();
        try {
            for (int index = 0; index < pool.getMaximumPoolSize(); index++) {
                connections.add(pool.getConnection());
            }
            return connections;
        } catch (SQLException exception) {
            closeAll(connections);
            throw exception;
        }
    }

    private void closeAll(List<Connection> connections) throws SQLException {
        for (Connection connection : connections) {
            connection.close();
        }
    }
}
