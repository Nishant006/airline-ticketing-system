package com.airline.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import com.airline.reservation.model.CreateScheduleCommand;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

class ScheduleIT extends IntegrationSupport {
  private static final String REQUEST =
      """
                    {"flightNumber":"XY101","originCode":"DXB","destinationCode":"LHR",
                     "departureTime":"09:30","arrivalTime":"13:45","aircraftId":"A320-001",
                     "daysOfOperation":["MONDAY","WEDNESDAY","FRIDAY"]}
                    """;

  @Test
  void createsScheduleAndAllWeekdaysInOneRequest() throws Exception {
    mvc.perform(
            post("/api/v1/admin/flight-schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.daysOfOperation.length()").value(3));
    assertThat(count("flight_schedule")).isEqualTo(1);
    assertThat(count("flight_schedule_day")).isEqualTo(3);
  }

  @Test
  void unknownReferenceCreatesNoPartialSchedule() throws Exception {
    mvc.perform(
            post("/api/v1/admin/flight-schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST.replace("A320-001", "UNKNOWN")))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.errorCode").value("UNKNOWN_REFERENCE"));
    assertThat(count("flight_schedule")).isZero();
    assertThat(count("flight_schedule_day")).isZero();
  }

  @Test
  void duplicateNumberIsAConflictNotASecondSchedule() throws Exception {
    schedule("XY101", "A320-001");
    mvc.perform(
            post("/api/v1/admin/flight-schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("DUPLICATE_SCHEDULE"));
    assertThat(count("flight_schedule")).isEqualTo(1);
    assertThat(count("flight_schedule_day")).isEqualTo(7);
  }

  @Test
  void libraryValidationRejectsDuplicateWeekdaysWithoutPersisting() throws Exception {
    mvc.perform(
            post("/api/v1/admin/flight-schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST.replace("\"WEDNESDAY\"", "\"MONDAY\"")))
        .andExpect(status().isBadRequest());
    assertThat(count("flight_schedule")).isZero();
  }

  @Test
  void sameOriginAndDestinationIsRejectedByServicePolicy() throws Exception {
    mvc.perform(
            post("/api/v1/admin/flight-schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST.replace("\"LHR\"", "\"DXB\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("SAME_ORIGIN_DESTINATION"));
    assertThat(count("flight_schedule")).isZero();
  }

  @Test
  void concurrentCreationHasOneWinnerAndNoOrphanDays() throws Exception {
    List<String> results =
        concurrently(
            12,
            ignored -> {
              try {
                schedule("XY101", "A320-001");
                return "CREATED";
              } catch (BusinessException exception) {
                assertThat(exception.getCode()).isEqualTo(ErrorCode.DUPLICATE_SCHEDULE);
                return "CONFLICT";
              }
            });
    assertThat(results).filteredOn("CREATED"::equals).hasSize(1);
    assertThat(results).filteredOn("CONFLICT"::equals).hasSize(11);
    assertThat(count("flight_schedule")).isEqualTo(1);
    assertThat(count("flight_schedule_day")).isEqualTo(7);
  }

  @Test
  void weekdayInsertionFailureRollsBackParentAndEarlierWeekdays() {
    jdbc.sql(
            """
                                ALTER TABLE flight_schedule_day ADD CONSTRAINT test_reject_wednesday
                                CHECK (day_of_week <> 3)
                                """)
        .update();
    try {
      assertThatThrownBy(
              () ->
                  schedules.create(
                      new CreateScheduleCommand(
                          "XY101",
                          "DXB",
                          "LHR",
                          LocalTime.of(9, 30),
                          LocalTime.of(13, 45),
                          "A320-001",
                          List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(count("flight_schedule")).isZero();
      assertThat(count("flight_schedule_day")).isZero();
    } finally {
      jdbc.sql("ALTER TABLE flight_schedule_day DROP CONSTRAINT test_reject_wednesday").update();
    }
  }
}
