# Entity relationships

This diagram matches V1 and the generated `schema.sql`. Reference data is in V2;
the Flyway history table is operational metadata and is excluded from the snapshot.

```mermaid
erDiagram
    AIRPORT ||--o{ FLIGHT_SCHEDULE : origin
    AIRPORT ||--o{ FLIGHT_SCHEDULE : destination
    AIRCRAFT ||--o{ FLIGHT_SCHEDULE : configures
    FLIGHT_SCHEDULE ||--|{ FLIGHT_SCHEDULE_DAY : operates
    FLIGHT_SCHEDULE ||--o{ FLIGHT_INSTANCE : materializes
    FLIGHT_INSTANCE ||--o{ BOOKING : receives
    BOOKING ||--|{ BOOKING_SEAT : allocates
    FLIGHT_INSTANCE ||--o{ BOOKING_SEAT : "inventories, via fk_seat_booking"
    AIRPORT {
        char3 iata_code PK
        varchar name
        varchar city
        varchar country
    }
    AIRCRAFT {
        varchar id PK
        varchar type
        smallint rows_count
        varchar seat_letters
    }
    FLIGHT_SCHEDULE {
        bigint id PK
        varchar flight_number UK
        char3 origin_code FK
        char3 destination_code FK
        time departure_time
        time arrival_time
        varchar aircraft_id FK
        timestamptz created_at
    }
    FLIGHT_SCHEDULE_DAY {
        bigint schedule_id PK,FK
        smallint day_of_week PK
    }
    FLIGHT_INSTANCE {
        bigint id PK
        bigint schedule_id FK
        date flight_date
        timestamptz created_at
    }
    BOOKING {
        bigint id PK
        uuid reference UK
        bigint flight_instance_id FK
        varchar status
        timestamptz created_at
        timestamptz cancelled_at
    }
    BOOKING_SEAT {
        bigint id PK
        bigint booking_id FK
        bigint flight_instance_id FK
        varchar seat_label
        text passenger_name
        varchar status
    }
```

The `|{` lower bounds describe application invariants: schedule creation includes
at least one day and booking includes at least one seat in one transaction.
Foreign keys alone do not enforce those minimum child counts.
`booking_seat` has no direct foreign key to `flight_instance`; its instance is
enforced through the composite `fk_seat_booking` described below.

## Keys and inventory invariants

`flight_instance(schedule_id,flight_date)` is unique. `booking(id,flight_instance_id)`
is additionally unique to support the composite allocation foreign key.
`booking_seat(booking_id,flight_instance_id)` references that pair, so an allocation
cannot silently belong to a different instance than its booking.

`uq_booking_seat_active` uniquely indexes `(flight_instance_id,seat_label)` **where
status is BOOKED**. Historical RELEASED rows can coexist with a newer active owner.
The fixed layout is derived from the aircraft's row count and seat letters; there
is no independently mutable seat inventory table/counter.

The route index supports search, the instance unique index supports instance
lookups, and `ix_seat_booking` supports cancellation/history lookup. V1 and the
snapshot contain complete check constraints, including UTC cancellation-state
consistency, canonical labels and valid weekdays.
