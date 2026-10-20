package com.airline.reservation.repository;

import com.airline.reservation.entity.Airport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AirportRepository extends JpaRepository<Airport, String> {}
