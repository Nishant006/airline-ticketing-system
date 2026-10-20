package com.airline.reservation.controller;

import com.airline.reservation.dto.request.CreateBookingRequest;
import com.airline.reservation.dto.response.BookingResponse;
import com.airline.reservation.service.BookingService;
import com.airline.reservation.service.CancellationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/bookings", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
public class BookingController {
  private final BookingService bookingService;
  private final CancellationService cancellationService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BookingResponse create(@Valid @RequestBody CreateBookingRequest request) {
    return BookingResponse.from(bookingService.create(request.toCommand()));
  }

  @PostMapping("/{bookingReference}/cancel")
  public BookingResponse cancel(@PathVariable UUID bookingReference) {
    BookingResponse response = BookingResponse.from(cancellationService.cancel(bookingReference));
    log.info(
        "booking_cancelled reference={} seats={}", bookingReference, response.passengerCount());
    return response;
  }
}
