package com.airline.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.model.CreateBookingCommand;
import com.airline.reservation.model.CreateBookingCommand.Passenger;
import com.airline.reservation.model.CreateScheduleCommand;
import com.airline.reservation.service.BookingService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class BookingIT extends IntegrationSupport {
  @Autowired private BookingService bookings;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void createsAtomicMultiSeatBookingWithSixResponseFields() throws Exception {
    schedule("XY101", "A320-001");
    mvc.perform(
            post("/api/v1/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                                {"flightNumber":"XY101","flightDate":"2027-03-02",
                                                 "passengers":[{"name":"Alpha","seat":"2B"},{"name":"Beta","seat":"2A"}]}
                                                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.length()").value(6))
        .andExpect(jsonPath("$.bookingReference").isString())
        .andExpect(jsonPath("$.flightNumber").value("XY101"))
        .andExpect(jsonPath("$.flightDate").value("2027-03-02"))
        .andExpect(jsonPath("$.passengerCount").value(2))
        .andExpect(jsonPath("$.assignedSeats[0]").value("2A"))
        .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));
    mvc.perform(get("/api/v1/flights/XY101/seat-map").param("date", TOMORROW.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seats[6].status").value("BOOKED"))
        .andExpect(jsonPath("$.seats[7].status").value("BOOKED"));
    mvc.perform(
            get("/api/v1/flights")
                .param("originCode", "DXB")
                .param("destinationCode", "LHR")
                .param("date", TOMORROW.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].availableSeats").value(178));
    assertThat(count("flight_instance")).isEqualTo(1);
    assertThat(count("booking")).isEqualTo(1);
    assertThat(count("booking_seat")).isEqualTo(2);
  }

  @Test
  void conflictRollsBackAllSeatsAndHeaderBeforeFreshSnapshotResponse() throws Exception {
    schedule("XY101", "A320-001");
    bookings.create(command("1B"));
    mvc.perform(
            post("/api/v1/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                                {"flightNumber":"XY101","flightDate":"2027-03-02",
                                                 "passengers":[{"name":"Alpha","seat":"1A"},{"name":"Beta","seat":"1B"}]}
                                                """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("SEAT_UNAVAILABLE"))
        .andExpect(jsonPath("$.currentlyUnavailableSeats[0]").value("1B"));
    assertThat(count("booking")).isEqualTo(1);
    assertThat(count("booking_seat")).isEqualTo(1);
    assertThat(bookings.create(command("1A")).assignedSeats()).containsExactly("1A");
  }

  @Test
  void actualSeatWritesUseDeterministicLabelOrder() {
    schedule("XY101", "A320-001");
    bookings.create(command("3C", "2A", "10A"));
    assertThat(
            jdbc.sql("SELECT seat_label FROM booking_seat ORDER BY id").query(String.class).list())
        .containsExactly("10A", "2A", "3C");
  }

  @Test
  void noSemanticValidationIsAppliedToPassengerNames() {
    schedule("XY101", "A320-001");
    List<Passenger> passengers =
        List.of(new Passenger("", "1A"), new Passenger("\u65c5\u5ba2 " + "x".repeat(1000), "1B"));
    bookings.create(new CreateBookingCommand("XY101", TOMORROW, passengers));
    assertThat(
            jdbc.sql("SELECT passenger_name FROM booking_seat ORDER BY seat_label")
                .query(String.class)
                .list())
        .containsExactly(passengers.getFirst().name(), passengers.getLast().name());
  }

  @Test
  void invalidAircraftSeatDoesNotEvenMaterialize() throws Exception {
    schedule("XY101", "AT72-001");
    mvc.perform(
            post("/api/v1/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                                {"flightNumber":"XY101","flightDate":"2027-03-02",
                                                 "passengers":[{"name":"Alpha","seat":"1F"}]}
                                                """))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.errorCode").value("SEAT_NOT_ON_AIRCRAFT"));
    assertThat(count("flight_instance")).isZero();
    assertThat(count("booking")).isZero();
    assertThatThrownBy(() -> bookings.create(command("1A", "1A")))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void unrelatedIntegrityFailureIsNotDisguisedAsSeatConflict(CapturedOutput output)
      throws Exception {
    schedule("XY101", "A320-001");
    jdbc.sql("ALTER TABLE booking_seat ADD CONSTRAINT test_reject_1a CHECK (seat_label <> '1A')")
        .update();
    try {
      MvcResult result =
          mvc.perform(
                  post("/api/v1/bookings")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          """
                                                    {"flightNumber":"XY101","flightDate":"2027-03-02",
                                                     "passengers":[{"name":"NotInErrorResponse","seat":"1A"}]}
                                                    """))
              .andExpect(status().isInternalServerError())
              .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
              .andReturn();
      assertThat(result.getResponse().getContentAsString())
          .doesNotContain("NotInErrorResponse", "test_reject_1a", "23514");
      assertThat(output.getAll())
          .contains(
              "rootType=org.postgresql.util.PSQLException",
              "sqlState=23514",
              "constraint=test_reject_1a",
              "location=com.airline.reservation.service.impl.");
      assertThat(output.getAll())
          .doesNotContain("NotInErrorResponse", "insert into booking_seat", "Failing row contains");
      assertThat(count("booking")).isZero();
      assertThat(count("booking_seat")).isZero();
    } finally {
      jdbc.sql("ALTER TABLE booking_seat DROP CONSTRAINT test_reject_1a").update();
    }
  }

  @Test
  void bookingUsesTheSame365DateWindowAndExactDepartureBoundary() throws Exception {
    schedule("XY101", "A320-001");
    for (LocalDate date : List.of(TODAY, TODAY.plusDays(364))) {
      bookings.create(new CreateBookingCommand("XY101", date, List.of(new Passenger("", "1A"))));
    }
    for (LocalDate date : List.of(TODAY.minusDays(1), TODAY.plusDays(365))) {
      mvc.perform(
              post("/api/v1/bookings")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                                                    {"flightNumber":"XY101","flightDate":"%s","passengers":[{"name":"","seat":"1A"}]}
                                                    """
                          .formatted(date)))
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.errorCode").value("DATE_OUT_OF_WINDOW"));
    }
    schedules.create(
        new CreateScheduleCommand(
            "XY102",
            "DXB",
            "LHR",
            LocalTime.of(8, 0),
            LocalTime.of(10, 0),
            "A320-001",
            List.of(DayOfWeek.values())));
    mvc.perform(
            post("/api/v1/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                                {"flightNumber":"XY102","flightDate":"2027-03-01","passengers":[{"name":"","seat":"1A"}]}
                                                """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("FLIGHT_DEPARTED"));
    assertThat(count("flight_instance")).isEqualTo(2);
  }

  @Test
  void coordinatorRejectsAmbientTransactionsBeforeMaterializationOrBooking() {
    schedule("XY101", "A320-001");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    assertThatThrownBy(
            () -> transaction.executeWithoutResult(ignored -> bookings.create(command("1A"))))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(count("flight_instance")).isZero();
    assertThat(count("booking")).isZero();
    assertThat(count("booking_seat")).isZero();
    assertThat(bookings.create(command("1A")).assignedSeats()).containsExactly("1A");
  }

  private CreateBookingCommand command(String... seats) {
    return new CreateBookingCommand(
        "XY101",
        TOMORROW,
        Arrays.stream(seats).map(seat -> new Passenger("Fixture", seat)).toList());
  }
}
