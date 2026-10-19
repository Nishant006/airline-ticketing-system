package com.airline.reservation.service;

import com.airline.reservation.config.BookingProperties;
import com.airline.reservation.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlightDatePolicyTest {
    private final Clock clock = Clock.fixed(Instant.parse("2027-03-01T08:00:00Z"), ZoneOffset.UTC);
    private final FlightDatePolicy policy = new FlightDatePolicy(clock, new BookingProperties(365));

    @Test
    void horizonContainsExactly365DatesEvenAcrossLeapYear() {
        LocalDate today = LocalDate.of(2027, 3, 1);
        assertThatCode(() -> policy.requireInWindow(today)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireInWindow(today.plusDays(364))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireInWindow(today.plusDays(365)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireInWindow(today.minusDays(1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void departureIsAnExactUtcBoundary() {
        LocalDate today = LocalDate.of(2027, 3, 1);
        assertThatCode(() -> policy.requireBeforeDeparture(today, LocalTime.of(8, 1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireBeforeDeparture(today, LocalTime.of(8, 0)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireBeforeDeparture(today, LocalTime.of(7, 59)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void usesUtcRegardlessOfClockZoneAndEvaluatesCurrentInstantEachTime() {
        Clock nonUtc = Clock.fixed(Instant.parse("2027-03-01T23:30:00Z"), ZoneOffset.ofHours(14));
        FlightDatePolicy utcPolicy = new FlightDatePolicy(nonUtc, new BookingProperties(365));
        assertThatCode(() -> utcPolicy.requireInWindow(LocalDate.of(2027, 3, 1)))
                .doesNotThrowAnyException();
        Clock changing = mock(Clock.class);
        when(changing.instant()).thenReturn(clock.instant(), clock.instant().plusSeconds(86400));
        FlightDatePolicy changingPolicy = new FlightDatePolicy(changing, new BookingProperties(365));
        assertThatCode(() -> changingPolicy.requireInWindow(LocalDate.of(2027, 3, 1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> changingPolicy.requireInWindow(LocalDate.of(2027, 3, 1)))
                .isInstanceOf(BusinessException.class);
    }
}
