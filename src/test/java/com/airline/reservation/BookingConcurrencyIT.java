package com.airline.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.airline.reservation.entity.Aircraft;
import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import com.airline.reservation.model.BookingResult;
import com.airline.reservation.model.CreateBookingCommand;
import com.airline.reservation.service.BookingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BookingConcurrencyIT extends IntegrationSupport {
  @Autowired private BookingService bookingService;

  @Test
  void concurrentFirstBookingsForOneSeatHaveExactlyOneOwner() throws Exception {
    schedule("XY101", "A320-001");
    List<Attempt> results = concurrently(24, ignored -> attempt(List.of("1A")));
    assertThat(results).filteredOn(Attempt::confirmed).hasSize(1);
    assertThat(count("flight_instance")).isEqualTo(1);
    assertThat(count("booking")).isEqualTo(1);
    assertThat(count("booking_seat")).isEqualTo(1);
  }

  @Test
  void reversedSeatSetsHaveOneWholeWinnerAndNoPartialBookings() throws Exception {
    schedule("XY101", "A320-001");
    List<Attempt> results =
        concurrently(
            24, index -> attempt(index % 2 == 0 ? List.of("1A", "1B") : List.of("1B", "1A")));
    assertThat(results).filteredOn(Attempt::confirmed).hasSize(1);
    assertThat(count("booking")).isEqualTo(1);
    assertThat(count("booking_seat")).isEqualTo(2);
  }

  @Test
  void overlappingSetsDoNotLeakTheirNoncompetingSeats() throws Exception {
    schedule("XY101", "A320-001");
    List<Attempt> results =
        concurrently(
            16, index -> attempt(index % 2 == 0 ? List.of("1A", "1B") : List.of("1B", "1C")));
    assertThat(results).filteredOn(Attempt::confirmed).hasSize(1);
    assertThat(count("booking_seat")).isEqualTo(2);
    BookingResult winner =
        results.stream().filter(Attempt::confirmed).findFirst().orElseThrow().response();
    String freeSeat = winner.assignedSeats().contains("1A") ? "1C" : "1A";
    assertThat(attempt(List.of(freeSeat)).confirmed()).isTrue();
  }

  @Test
  void disjointSeatsCanAllBeBookedConcurrently() throws Exception {
    schedule("XY101", "A320-001");
    List<Attempt> results = concurrently(24, index -> attempt(List.of((index + 1) + "A")));
    assertThat(results).allMatch(Attempt::confirmed);
    assertThat(count("booking")).isEqualTo(24);
    assertThat(count("booking_seat")).isEqualTo(24);
  }

  @Test
  void fullFlightContentionNeverExceedsConfiguredCapacity() throws Exception {
    schedule("XY101", "AT72-001");
    List<String> labels = new Aircraft("AT72-001", "ATR", 18, "ABCD").seatLabels();
    List<Attempt> results = concurrently(12, ignored -> attempt(labels));
    assertThat(results).filteredOn(Attempt::confirmed).hasSize(1);
    assertThat(count("booking")).isEqualTo(1);
    assertThat(count("booking_seat")).isEqualTo(72);
    assertThat(
            jdbc.sql(
                    """
                                        SELECT count(*) FROM (
                                          SELECT flight_instance_id, seat_label FROM booking_seat WHERE status = 'BOOKED'
                                          GROUP BY flight_instance_id, seat_label HAVING count(*) > 1
                                        ) duplicates
                                        """)
                .query(Integer.class)
                .single())
        .isZero();
  }

  private Attempt attempt(List<String> labels) {
    try {
      BookingResult response =
          bookingService.create(
              new CreateBookingCommand(
                  "XY101",
                  TOMORROW,
                  labels.stream()
                      .map(label -> new CreateBookingCommand.Passenger("Synthetic", label))
                      .toList()));
      return new Attempt(response);
    } catch (BusinessException exception) {
      assertThat(exception.getCode()).isEqualTo(ErrorCode.SEAT_UNAVAILABLE);
      return new Attempt(null);
    }
  }

  private record Attempt(BookingResult response) {
    boolean confirmed() {
      return response != null;
    }
  }
}
