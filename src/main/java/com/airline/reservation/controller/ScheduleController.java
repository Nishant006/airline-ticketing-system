package com.airline.reservation.controller;

import com.airline.reservation.dto.request.CreateScheduleRequest;
import com.airline.reservation.dto.response.ScheduleResponse;
import com.airline.reservation.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        value = "/api/v1/admin/flight-schedules",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
public class ScheduleController {
    private final ScheduleService scheduleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleResponse create(@Valid @RequestBody CreateScheduleRequest request) {
        ScheduleResponse created = ScheduleResponse.from(scheduleService.create(request.toCommand()));
        log.info("schedule_created flight={}", created.flightNumber());
        return created;
    }
}
