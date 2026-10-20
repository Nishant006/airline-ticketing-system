package com.airline.reservation.service.impl;

import com.airline.reservation.entity.Aircraft;
import com.airline.reservation.entity.Airport;
import com.airline.reservation.entity.FlightSchedule;
import com.airline.reservation.exception.BusinessException;
import com.airline.reservation.exception.ErrorCode;
import com.airline.reservation.exception.PersistenceConstraints;
import com.airline.reservation.model.CreateScheduleCommand;
import com.airline.reservation.model.ScheduleDetails;
import com.airline.reservation.repository.AircraftRepository;
import com.airline.reservation.repository.AirportRepository;
import com.airline.reservation.repository.FlightScheduleRepository;
import com.airline.reservation.service.ScheduleService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {
  private final FlightScheduleRepository flightScheduleRepository;
  private final AirportRepository airportRepository;
  private final AircraftRepository aircraftRepository;

  @Override
  @Transactional
  public ScheduleDetails create(CreateScheduleCommand command) {
    if (command.originCode().equals(command.destinationCode())) {
      throw new BusinessException(
          ErrorCode.SAME_ORIGIN_DESTINATION, "Origin and destination must differ");
    }
    FlightSchedule schedule = buildSchedule(command);
    saveSchedule(schedule);
    return new ScheduleDetails(
        schedule.getFlightNumber(),
        schedule.getOrigin().getCode(),
        schedule.getDestination().getCode(),
        schedule.getDepartureTime(),
        schedule.getArrivalTime(),
        schedule.getAircraft().getId(),
        command.daysOfOperation());
  }

  private FlightSchedule buildSchedule(CreateScheduleCommand command) {
    Airport origin =
        airportRepository.findById(command.originCode()).orElseThrow(this::unknownReference);
    Airport destination =
        airportRepository.findById(command.destinationCode()).orElseThrow(this::unknownReference);
    Aircraft aircraft =
        aircraftRepository.findById(command.aircraftId()).orElseThrow(this::unknownReference);
    return FlightSchedule.builder()
        .flightNumber(command.flightNumber())
        .origin(origin)
        .destination(destination)
        .departureTime(command.departureTime())
        .arrivalTime(command.arrivalTime())
        .aircraft(aircraft)
        .daysOfOperation(Set.copyOf(command.daysOfOperation()))
        .build();
  }

  private void saveSchedule(FlightSchedule schedule) {
    try {
      flightScheduleRepository.saveAndFlush(schedule);
    } catch (DataIntegrityViolationException exception) {
      if (PersistenceConstraints.isUniqueViolationOf(exception, "uq_schedule_flight_number")) {
        throw new BusinessException(
            ErrorCode.DUPLICATE_SCHEDULE, "Flight number already has a schedule");
      }
      throw exception;
    }
  }

  private BusinessException unknownReference() {
    return new BusinessException(
        ErrorCode.UNKNOWN_REFERENCE, "Referenced airport or aircraft does not exist");
  }
}
