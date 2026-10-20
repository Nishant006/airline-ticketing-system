package com.airline.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import com.airline.reservation.model.BookingResult;
import com.airline.reservation.model.BookingStatus;
import com.airline.reservation.model.CreateBookingCommand;
import com.airline.reservation.service.BookingService;
import com.airline.reservation.service.CancellationService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class CancellationIT extends IntegrationSupport {
  @Autowired private BookingService bookingService;
  @Autowired private CancellationService cancellationService;

  @Test
  void cancellationReleasesAllSeatsAndMakesBothReadApisCurrent() throws Exception {
    schedule("XY101", "A320-001");
    BookingResult booking = book(TOMORROW);
    statistics().clear();
    BookingResult cancelled = cancellationService.cancel(booking.bookingReference());
    assertThat(statistics().getPrepareStatementCount()).isEqualTo(4);
    assertThat(cancelled.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(cancelled.assignedSeats()).containsExactly("1A", "1B");
    assertThat(jdbc.sql("SELECT status FROM booking").query(String.class).single())
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.sql("SELECT cancelled_at IS NOT NULL FROM booking").query(Boolean.class).single())
        .isTrue();
    assertThat(jdbc.sql("SELECT status FROM booking_seat").query(String.class).list())
        .containsOnly("RELEASED");
    mvc.perform(get("/api/v1/flights/XY101/seat-map").param("date", TOMORROW.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
    mvc.perform(
            get("/api/v1/flights")
                .param("originCode", "DXB")
                .param("destinationCode", "LHR")
                .param("date", TOMORROW.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].availableSeats").value(180));
    assertThat(book(TOMORROW).bookingReference()).isNotEqualTo(booking.bookingReference());
    assertThat(count("booking_seat")).isEqualTo(4);
  }

  @Test
  void cancellationApiReturnsTheBookingRepresentationAndRepeatIs409() throws Exception {
    schedule("XY101", "A320-001");
    UUID reference = book(TOMORROW).bookingReference();
    mvc.perform(post("/api/v1/bookings/{reference}/cancel", reference))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"))
        .andExpect(jsonPath("$.passengerCount").value(2));
    mvc.perform(post("/api/v1/bookings/{reference}/cancel", reference))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("ALREADY_CANCELLED"));
    mvc.perform(post("/api/v1/bookings/{reference}/cancel", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("BOOKING_NOT_FOUND"));
  }

  @Test
  void departedBookingCannotBeCancelled() throws Exception {
    schedule("XY101", "A320-001");
    UUID reference = book(TODAY).bookingReference();
    jdbc.sql("UPDATE flight_instance SET flight_date = :past")
        .param("past", TODAY.minusDays(1))
        .update();
    mvc.perform(post("/api/v1/bookings/{reference}/cancel", reference))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("FLIGHT_DEPARTED"));
    assertThat(jdbc.sql("SELECT status FROM booking").query(String.class).single())
        .isEqualTo("CONFIRMED");
  }

  @Test
  void releaseFailureRollsBackStatusAndAllSeatChanges() {
    schedule("XY101", "A320-001");
    UUID reference = book(TOMORROW).bookingReference();
    jdbc.sql(
            """
                                ALTER TABLE booking_seat ADD CONSTRAINT test_reject_release
                                CHECK (seat_label <> '1B' OR status <> 'RELEASED')
                                """)
        .update();
    try {
      assertThatThrownBy(() -> cancellationService.cancel(reference))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(jdbc.sql("SELECT status FROM booking").query(String.class).single())
          .isEqualTo("CONFIRMED");
      assertThat(jdbc.sql("SELECT status FROM booking_seat").query(String.class).list())
          .containsOnly("BOOKED");
    } finally {
      jdbc.sql("ALTER TABLE booking_seat DROP CONSTRAINT test_reject_release").update();
    }
  }

  @Test
  void simultaneousCancellationsHaveOneWinner() throws Exception {
    schedule("XY101", "A320-001");
    UUID reference = book(TOMORROW).bookingReference();
    List<Boolean> results =
        concurrently(
            24,
            ignored -> {
              try {
                cancellationService.cancel(reference);
                return true;
              } catch (BusinessException exception) {
                assertThat(exception.getCode()).isEqualTo(ErrorCode.ALREADY_CANCELLED);
                return false;
              }
            });
    assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
    assertThat(jdbc.sql("SELECT status FROM booking_seat").query(String.class).list())
        .containsOnly("RELEASED");
  }

  @Test
  void cancellationRacingWithRebookingNeverCreatesTwoOwners() throws Exception {
    schedule("XY101", "A320-001");
    UUID reference = book(TOMORROW).bookingReference();
    List<BookingResult> results =
        concurrently(
            2,
            index -> {
              if (index == 0) {
                return cancellationService.cancel(reference);
              }
              try {
                return book(TOMORROW);
              } catch (BusinessException exception) {
                assertThat(exception.getCode()).isEqualTo(ErrorCode.SEAT_UNAVAILABLE);
                return null;
              }
            });
    if (results.get(1) == null) {
      book(TOMORROW);
    }
    assertThat(
            jdbc.sql("SELECT count(*) FROM booking WHERE status = 'CONFIRMED'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM booking_seat WHERE status = 'BOOKED'")
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  private BookingResult book(LocalDate date) {
    return bookingService.create(
        new CreateBookingCommand(
            "XY101",
            date,
            List.of(
                new CreateBookingCommand.Passenger("Alpha", "1A"),
                new CreateBookingCommand.Passenger("Beta", "1B"))));
  }
}
