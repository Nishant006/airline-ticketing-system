package com.airline.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.airline.reservation.model.CreateScheduleCommand;
import com.airline.reservation.model.SeatMap;
import com.airline.reservation.service.FlightService;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SeatMapIT extends IntegrationSupport {
  @Autowired private FlightService flights;

  @Test
  void neverBookedAircraftExposesEverySeatWithoutCreatingAnInstance() throws Exception {
    schedule("XY101", "A320-001");
    mvc.perform(get("/api/v1/flights/XY101/seat-map").param("date", TOMORROW.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seats.length()").value(180))
        .andExpect(jsonPath("$.seats[0].label").value("1A"))
        .andExpect(jsonPath("$.seats[179].label").value("30F"))
        .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
        .andExpect(jsonPath("$.seats[0].passengerName").doesNotExist());
    assertThat(count("flight_instance")).isZero();
  }

  @Test
  void reflectsCommittedActiveAndReleasedAllocationsWithAlternateConfiguration() {
    schedule("XY101", "AT72-001");
    long instance = instance("XY101", TOMORROW);
    rawBooking(instance, List.of("1A", "18D"));
    statistics().clear();
    SeatMap map = flights.seatMap("XY101", TOMORROW);
    assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
    assertThat(statistics().getEntityFetchCount()).isZero();
    assertThat(map.seats()).hasSize(72);
    assertThat(map.seats()).filteredOn(seat -> seat.status() == SeatMap.Status.BOOKED).hasSize(2);
    jdbc.sql("UPDATE booking_seat SET status = 'RELEASED' WHERE seat_label = '1A'").update();
    assertThat(flights.seatMap("XY101", TOMORROW).seats().getFirst().status())
        .isEqualTo(SeatMap.Status.AVAILABLE);
  }

  @Test
  void absentOrNonoperatingFlightsReturn404() throws Exception {
    schedules.create(
        new CreateScheduleCommand(
            "XY101",
            "DXB",
            "LHR",
            LocalTime.of(9, 30),
            LocalTime.of(13, 45),
            "AT72-001",
            List.of(DayOfWeek.MONDAY)));
    mvc.perform(get("/api/v1/flights/XY101/seat-map").param("date", TOMORROW.toString()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/flights/XY999/seat-map").param("date", TODAY.toString()))
        .andExpect(status().isNotFound());
  }
}
