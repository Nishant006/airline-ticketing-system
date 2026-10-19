package com.airline.reservation;

import com.airline.reservation.entity.Aircraft;
import com.airline.reservation.model.CreateScheduleCommand;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlightSearchIT extends IntegrationSupport {

    @Test
    void matchesOnlyRouteAndOperatingDayAndDoesNotMaterialize() throws Exception {
        schedules.create(
                new CreateScheduleCommand(
                        "XY101",
                        "DXB",
                        "LHR",
                        LocalTime.of(9, 30),
                        LocalTime.of(13, 45),
                        "A320-001",
                        List.of(DayOfWeek.MONDAY)));
        mvc.perform(
                        get("/api/v1/flights")
                                .param("originCode", "DXB")
                                .param("destinationCode", "LHR")
                                .param("date", TODAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].length()").value(4))
                .andExpect(jsonPath("$[0].flightNumber").value("XY101"))
                .andExpect(jsonPath("$[0].departureTime").value("09:30"))
                .andExpect(jsonPath("$[0].arrivalTime").value("13:45"))
                .andExpect(jsonPath("$[0].availableSeats").value(180));
        mvc.perform(
                        get("/api/v1/flights")
                                .param("originCode", "DXB")
                                .param("destinationCode", "LHR")
                                .param("date", TOMORROW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(
                        get("/api/v1/flights")
                                .param("originCode", "BOM")
                                .param("destinationCode", "SIN")
                                .param("date", TODAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(count("flight_instance")).isZero();
    }

    @Test
    void windowBoundariesAreEnforcedOnSearch() throws Exception {
        for (LocalDate date : List.of(TODAY, TODAY.plusDays(364))) {
            mvc.perform(
                            get("/api/v1/flights")
                                    .param("originCode", "DXB")
                                    .param("destinationCode", "LHR")
                                    .param("date", date.toString()))
                    .andExpect(status().isOk());
        }
        for (LocalDate date : List.of(TODAY.minusDays(1), TODAY.plusDays(365))) {
            mvc.perform(
                            get("/api/v1/flights")
                                    .param("originCode", "DXB")
                                    .param("destinationCode", "LHR")
                                    .param("date", date.toString()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorCode").value("DATE_OUT_OF_WINDOW"));
        }
    }

    @Test
    void countsOnlyActiveAllocationsAndRetainsFullyBookedFlights() throws Exception {
        schedule("XY101", "AT72-001");
        long instance = instance("XY101", TOMORROW);
        rawBooking(instance, new Aircraft("AT72-001", "ATR", 18, "ABCD").seatLabels());
        mvc.perform(
                        get("/api/v1/flights")
                                .param("originCode", "DXB")
                                .param("destinationCode", "LHR")
                                .param("date", TOMORROW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableSeats").value(0));
        jdbc.sql("UPDATE booking_seat SET status = 'RELEASED' WHERE seat_label = '1A'").update();
        mvc.perform(
                        get("/api/v1/flights")
                                .param("originCode", "DXB")
                                .param("destinationCode", "LHR")
                                .param("date", TOMORROW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableSeats").value(1));
    }

    @Test
    void multiResultSearchUsesOneSqlStatement() throws Exception {
        schedule("XY101", "A320-001");
        schedule("XY102", "AT72-001");
        statistics().clear();
        mvc.perform(
                        get("/api/v1/flights")
                                .param("originCode", "DXB")
                                .param("destinationCode", "LHR")
                                .param("date", TOMORROW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics().getEntityLoadCount()).isZero();
    }
}
