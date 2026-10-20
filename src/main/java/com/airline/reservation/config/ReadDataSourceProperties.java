package com.airline.reservation.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("airline.datasource.read")
public record ReadDataSourceProperties(
    @Min(1) int maximumPoolSize,
    @Min(250) long connectionTimeoutMs,
    @Min(1) int statementTimeoutMs,
    @Min(1) int lockTimeoutMs) {}
