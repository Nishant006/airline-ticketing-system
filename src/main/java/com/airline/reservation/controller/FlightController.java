package com.airline.reservation.controller;

import com.airline.reservation.dto.response.FlightSearchResponse;
import com.airline.reservation.dto.response.SeatMapResponse;
import com.airline.reservation.service.FlightService;
import com.airline.reservation.validation.InputFormats;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/flights", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FlightController {
    private final FlightService flightService;

    @GetMapping("/{flightNumber}/seat-map")
    public SeatMapResponse seatMap(
            @PathVariable @Pattern(regexp = InputFormats.FLIGHT_NUMBER) String flightNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return SeatMapResponse.from(flightService.seatMap(flightNumber, date));
    }

    @GetMapping
    public List<FlightSearchResponse> search(
            @RequestParam @Pattern(regexp = InputFormats.AIRPORT_CODE) String originCode,
            @RequestParam @Pattern(regexp = InputFormats.AIRPORT_CODE) String destinationCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return flightService.search(originCode, destinationCode, date).stream()
                .map(FlightSearchResponse::from)
                .toList();
    }
}
