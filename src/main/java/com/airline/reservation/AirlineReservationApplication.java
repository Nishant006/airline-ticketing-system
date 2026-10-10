package com.airline.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AirlineReservationApplication {

  public static void main(String[] args) {
    SpringApplication.run(AirlineReservationApplication.class, args);
  }
}
