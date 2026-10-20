package com.airline.reservation.service;

import com.airline.reservation.model.CreateScheduleCommand;
import com.airline.reservation.model.ScheduleDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public interface ScheduleService {
  ScheduleDetails create(@NotNull @Valid CreateScheduleCommand command);
}
