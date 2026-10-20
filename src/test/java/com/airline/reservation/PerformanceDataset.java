package com.airline.reservation;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PerformanceDataset {
  private PerformanceDataset() {}

  static void seed(JdbcClient jdbc, LocalDate startDate) {
    jdbc.sql(
            """
                                INSERT INTO flight_schedule
                                    (flight_number, origin_code, destination_code, departure_time, arrival_time, aircraft_id)
                                SELECT 'PF' || lpad(n::text, 4, '0'), CASE WHEN n <= 100 THEN 'DXB' ELSE 'BOM' END,
                                       CASE WHEN n <= 100 THEN 'LHR' ELSE 'SIN' END, '09:30'::time, '13:45'::time, 'A320-001'
                                FROM generate_series(1, 500) n
                                """)
        .update();
    jdbc.sql(
            """
                                INSERT INTO flight_schedule_day
                                SELECT s.id, d FROM flight_schedule s CROSS JOIN generate_series(1, 7) d
                                """)
        .update();
    jdbc.sql(
            """
                                INSERT INTO flight_instance (schedule_id, flight_date)
                                SELECT s.id, :date + d FROM flight_schedule s CROSS JOIN generate_series(0, 29) d
                                """)
        .param("date", startDate)
        .update();
    jdbc.sql(
            """
                                INSERT INTO booking (reference, flight_instance_id, status, cancelled_at)
                                SELECT md5(i.id::text || '-' || k)::uuid, i.id,
                                       CASE WHEN k = 1 THEN 'CONFIRMED' ELSE 'CANCELLED' END,
                                       CASE WHEN k = 2 THEN now() ELSE NULL END
                                FROM flight_instance i CROSS JOIN generate_series(1, 2) k
                                """)
        .update();
    jdbc.sql(
            """
                                INSERT INTO booking_seat (booking_id, flight_instance_id, seat_label, passenger_name, status)
                                SELECT b.id, b.flight_instance_id, n::text || 'A', 'Synthetic',
                                       CASE WHEN b.status = 'CONFIRMED' THEN 'BOOKED' ELSE 'RELEASED' END
                                FROM booking b CROSS JOIN generate_series(1, 20) n
                                """)
        .update();
    for (String table :
        List.of(
            "flight_schedule",
            "flight_schedule_day",
            "flight_instance",
            "booking",
            "booking_seat")) {
      jdbc.sql("ANALYZE " + table).update();
    }
  }
}
