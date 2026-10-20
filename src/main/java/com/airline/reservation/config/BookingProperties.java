package com.airline.reservation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("airline.booking")
public record BookingProperties(@Min(365) @Max(365) int windowDays) {}
