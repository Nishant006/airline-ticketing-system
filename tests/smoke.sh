#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-http://localhost:8080}"
response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT
flight="$(python3 -c 'import uuid; print("SM" + uuid.uuid4().hex[:6].upper())')"
travel_date="$(python3 -c 'from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)+timedelta(days=1)).date())')"

request() {
  local expected="$1" method="$2" path="$3" body="${4:-}"
  local status
  if [ -n "$body" ]; then
    status="$(curl --silent --show-error --max-time 30 --output "$response_file" \
      --write-out '%{http_code}' --request "$method" --header 'Content-Type: application/json' \
      --data "$body" "$base_url$path")"
  else
    status="$(curl --silent --show-error --max-time 30 --output "$response_file" \
      --write-out '%{http_code}' --request "$method" "$base_url$path")"
  fi
  if [ "$status" != "$expected" ]; then
    printf 'Expected HTTP %s, got %s: %s %s\n' "$expected" "$status" "$method" "$path" >&2
    cat "$response_file" >&2
    exit 1
  fi
}

check() {
  python3 - "$response_file" "$1" "$flight" "$travel_date" <<'PY'
import json
import sys
import uuid

with open(sys.argv[1]) as stream:
    data = json.load(stream)
mode, flight, travel_date = sys.argv[2:]
if mode == "search":
    result = next(item for item in data if item["flightNumber"] == flight)
    assert set(result) == {"flightNumber", "departureTime", "arrivalTime", "availableSeats"}, result
    assert result["departureTime"] == "09:30" and result["arrivalTime"] == "13:45", result
    assert result["availableSeats"] == 180, result
elif mode == "available":
    labels = [f"{row}{letter}" for row in range(1, 31) for letter in "ABCDEF"]
    assert [seat["label"] for seat in data["seats"]] == labels, data
    assert all(seat["status"] == "AVAILABLE" for seat in data["seats"]), data
elif mode == "booked":
    assert [seat["status"] for seat in data["seats"][:2]] == ["BOOKED", "BOOKED"], data
elif mode == "booking":
    assert set(data) == {"bookingReference", "flightNumber", "flightDate", "passengerCount", "assignedSeats", "bookingStatus"}, data
    uuid.UUID(data["bookingReference"])
    assert data["flightNumber"] == flight and data["flightDate"] == travel_date, data
    assert data["bookingStatus"] == "CONFIRMED" and data["passengerCount"] == 2, data
    assert data["assignedSeats"] == ["1A", "1B"], data
    print(data["bookingReference"])
elif mode == "conflict":
    assert data["errorCode"] == "SEAT_UNAVAILABLE", data
elif mode == "cancelled":
    assert data["bookingStatus"] == "CANCELLED" and data["passengerCount"] == 2, data
else:
    raise AssertionError(f"Unknown response check: {mode}")
PY
}

request 200 GET /actuator/health/readiness
request 201 POST /api/v1/admin/flight-schedules \
  "{\"flightNumber\":\"$flight\",\"originCode\":\"DXB\",\"destinationCode\":\"LHR\",\"departureTime\":\"09:30\",\"arrivalTime\":\"13:45\",\"aircraftId\":\"A320-001\",\"daysOfOperation\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}"
request 200 GET "/api/v1/flights?originCode=DXB&destinationCode=LHR&date=$travel_date"
check search
request 200 GET "/api/v1/flights/$flight/seat-map?date=$travel_date"
check available
booking="{\"flightNumber\":\"$flight\",\"flightDate\":\"$travel_date\",\"passengers\":[{\"name\":\"Alpha\",\"seat\":\"1A\"},{\"name\":\"Beta\",\"seat\":\"1B\"}]}"
request 201 POST /api/v1/bookings "$booking"
reference="$(check booking)"
request 200 GET "/api/v1/flights/$flight/seat-map?date=$travel_date"
check booked
request 409 POST /api/v1/bookings "$booking"
check conflict
request 200 POST "/api/v1/bookings/$reference/cancel"
check cancelled
request 200 GET "/api/v1/flights/$flight/seat-map?date=$travel_date"
check available
request 201 POST /api/v1/bookings "$booking"
second_reference="$(check booking)"
request 200 POST "/api/v1/bookings/$second_reference/cancel"
check cancelled
printf 'PASS: schedule, search, seat map, atomic booking, conflict, cancellation and rebooking (%s, %s)\n' "$flight" "$travel_date"
