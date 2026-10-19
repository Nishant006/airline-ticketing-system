package com.airline.reservation.controller;

import com.airline.reservation.exception.GlobalExceptionHandler;
import com.airline.reservation.model.ScheduleDetails;
import com.airline.reservation.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScheduleController.class)
@Import(GlobalExceptionHandler.class)
class ScheduleControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ScheduleService schedules;

    @Test
    void validatesAllRequiredFieldsBeforeCallingApplication() throws Exception {
        mvc.perform(
                        post("/api/v1/admin/flight-schedules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verifyNoInteractions(schedules);
    }

    @Test
    void acceptsTheSevenScheduleFieldsAndFormatsTimesAsHoursAndMinutes() throws Exception {
        given(schedules.create(any()))
                .willReturn(
                        new ScheduleDetails(
                                "XY101",
                                "DXB",
                                "LHR",
                                LocalTime.of(9, 30),
                                LocalTime.of(13, 45),
                                "A320-001",
                                List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)));
        mvc.perform(
                        post("/api/v1/admin/flight-schedules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                                {"flightNumber":"XY101","originCode":"DXB","destinationCode":"LHR",
                                                 "departureTime":"09:30","arrivalTime":"13:45","aircraftId":"A320-001",
                                                 "daysOfOperation":["MONDAY","WEDNESDAY","FRIDAY"]}
                                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flightNumber").value("XY101"))
                .andExpect(jsonPath("$.departureTime").value("09:30"));
    }
}
