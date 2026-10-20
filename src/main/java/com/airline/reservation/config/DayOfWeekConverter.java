package com.airline.reservation.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.DayOfWeek;

@Converter
public class DayOfWeekConverter implements AttributeConverter<DayOfWeek, Short> {
  @Override
  public Short convertToDatabaseColumn(DayOfWeek day) {
    return day == null ? null : (short) day.getValue();
  }

  @Override
  public DayOfWeek convertToEntityAttribute(Short value) {
    return value == null ? null : DayOfWeek.of(value);
  }
}
