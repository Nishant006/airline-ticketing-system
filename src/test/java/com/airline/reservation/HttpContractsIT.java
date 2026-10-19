package com.airline.reservation;

import com.airline.reservation.config.RequestCorrelationFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class HttpContractsIT extends IntegrationSupport {
    @Test
    void malformedAndMissingInputUsesProblemDetailsWithoutEchoingPayload() throws Exception {
        for (String body :
                List.of(
                        "{",
                        "{}",
                        """
                                {"flightNumber":"XY101","flightDate":"2027-03-02","passengers":[null]}
                                """,
                        """
                                {"flightNumber":"XY101","flightDate":"2027-03-02","passengers":[{"name":null,"seat":"1A"}]}
                                """,
                        """
                                {"flightNumber":"XY101","flightDate":"2027-03-02","passengers":[{"name":"SensitiveFixture","seat":"01A"}]}
                                """)) {
            mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/api/v1/flights").param("originCode", "DXB").param("destinationCode", "LHR"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/flights")
                                .param("originCode", "dxb")
                                .param("destinationCode", "LHR")
                                .param("date", TODAY.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/flights")
                                .param("originCode", "DXB")
                                .param("destinationCode", "LHR")
                                .param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/bookings/not-a-uuid/cancel")).andExpect(status().isBadRequest());
    }

    @Test
    void protocolFailuresRetainTheirStatusAndSpecificCode() throws Exception {
        mvc.perform(put("/api/v1/bookings"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.TEXT_PLAIN).content("bad"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/api/v1/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_NOT_FOUND"));
    }

    @Test
    void unsupportedResponseTypesAreRejectedBeforeAnyWrite() throws Exception {
        mvc.perform(
                        post("/api/v1/admin/flight-schedules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_XML)
                                .content(
                                        """
                                                {"flightNumber":"XY101","originCode":"DXB","destinationCode":"LHR",
                                                 "departureTime":"09:30","arrivalTime":"13:45","aircraftId":"A320-001",
                                                 "daysOfOperation":["TUESDAY"]}
                                                """))
                .andExpect(status().isNotAcceptable());
        assertThat(count("flight_schedule")).isZero();
        schedule("XY101", "A320-001");
        String body =
                """
                        {"flightNumber":"XY101","flightDate":"2027-03-02",
                         "passengers":[{"name":"Fixture","seat":"1A"}]}
                        """;
        mvc.perform(
                        post("/api/v1/bookings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_XML)
                                .content(body))
                .andExpect(status().isNotAcceptable());
        assertThat(count("flight_instance")).isZero();
        assertThat(count("booking")).isZero();
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        UUID reference = jdbc.sql("SELECT reference FROM booking").query(UUID.class).single();
        mvc.perform(
                        post("/api/v1/bookings/{reference}/cancel", reference)
                                .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
        assertThat(jdbc.sql("SELECT status FROM booking").query(String.class).single())
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT status FROM booking_seat").query(String.class).single())
                .isEqualTo("BOOKED");
    }

    @Test
    void scheduleClocksAreStrictRatherThanSilentlyNormalized() throws Exception {
        for (String field : List.of("departureTime", "arrivalTime")) {
            for (String invalid : List.of("24:00", "25:00", "23:60", "12:00:01", "1:00")) {
                String departure = field.equals("departureTime") ? invalid : "09:30";
                String arrival = field.equals("arrivalTime") ? invalid : "13:45";
                mvc.perform(
                                post("/api/v1/admin/flight-schedules")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                        {"flightNumber":"XY101","originCode":"DXB","destinationCode":"LHR",
                                                         "departureTime":"%s","arrivalTime":"%s","aircraftId":"A320-001",
                                                         "daysOfOperation":["TUESDAY"]}
                                                        """
                                                        .formatted(departure, arrival)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
            }
        }
        assertThat(count("flight_schedule")).isZero();
        mvc.perform(
                        post("/api/v1/admin/flight-schedules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                                {"flightNumber":"XY101","originCode":"DXB","destinationCode":"LHR",
                                                 "departureTime":"00:00","arrivalTime":"23:59","aircraftId":"A320-001",
                                                 "daysOfOperation":["TUESDAY"]}
                                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departureTime").value("00:00"))
                .andExpect(jsonPath("$.arrivalTime").value("23:59"));
    }

    @Test
    void correlationIsEchoedGeneratedValidatedAndAlwaysCleared(CapturedOutput output)
            throws Exception {
        mvc.perform(get("/api/v1/unknown").header(RequestCorrelationFilter.HEADER, "audit_123"))
                .andExpect(header().string(RequestCorrelationFilter.HEADER, "audit_123"));
        assertThat(MDC.get("correlationId")).isNull();
        MvcResult result = mvc.perform(get("/api/v1/unknown")).andReturn();
        assertThat(UUID.fromString(result.getResponse().getHeader(RequestCorrelationFilter.HEADER)))
                .isNotNull();
        for (String invalid : List.of(" ", "x".repeat(65), "private caller value")) {
            mvc.perform(get("/api/v1/unknown").header(RequestCorrelationFilter.HEADER, invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/api/v1/unknown").header(RequestCorrelationFilter.HEADER, "one", "two"))
                .andExpect(status().isBadRequest());
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("instanceId")).isNull();
        assertThat(output.getAll()).doesNotContain("private caller value");
    }

    @Test
    void domainErrorsAndLogsNeverContainPassengerNames(CapturedOutput output) throws Exception {
        schedule("XY101", "A320-001");
        String body =
                """
                        {"flightNumber":"XY101","flightDate":"2027-03-02",
                         "passengers":[{"name":"SensitiveFixture","seat":"1A"},{"name":"","seat":"1A"}]}
                        """;
        MvcResult result =
                mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("DUPLICATE_SEAT"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SensitiveFixture");
        assertThat(output.getAll()).doesNotContain("SensitiveFixture");
    }
}
