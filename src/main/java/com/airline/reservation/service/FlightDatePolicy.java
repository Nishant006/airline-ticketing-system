package com.airline.reservation.service;

import com.airline.reservation.config.BookingProperties;
import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FlightDatePolicy {
  private final Clock clock;
  private final BookingProperties properties;

  public void requireInWindow(LocalDate date) {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (date.isBefore(today) || !date.isBefore(today.plusDays(properties.windowDays()))) {
      throw new BusinessException(
          ErrorCode.DATE_OUT_OF_WINDOW, "Travel date is outside the booking window");
    }
  }

  public void requireBeforeDeparture(LocalDate date, LocalTime departureTime) {
    Instant departure = date.atTime(departureTime).toInstant(ZoneOffset.UTC);
    if (!clock.instant().isBefore(departure)) {
      throw new BusinessException(ErrorCode.FLIGHT_DEPARTED, "Flight has already departed");
    }
  }
}
