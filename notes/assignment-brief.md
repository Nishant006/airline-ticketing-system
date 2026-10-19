# Assignment Brief — VERBATIM SOURCE OF TRUTH

> **Do not edit. Do not paraphrase.** This is the exact text supplied by the hiring team.
> Every audit of `spec.md`, `hld/`, `lld/`, `db/` and `README.md` must be performed
> **against this file**, section by section, line by line.
>
> Stored under `.notes/` (gitignored) deliberately: the submitted repository should not
> republish the company's assignment text. It is working material, not a deliverable.

---

Backend Engineering Assignment – Airline
Ticketing System

## Objective

Design and implement the backend for a single-airline airline reservation system.
The system should support flight schedule management, flight search, seat inventory management,
ticket booking, and booking cancellation.
The objective of this assignment is to evaluate:
System design skills
Database design
Backend architecture
API design
Algorithmic thinking
Concurrency handling
Code quality
Documentation
This assignment intentionally limits its scope. Any functionality not explicitly mentioned should be
considered out of scope and treated as a black box.

## Scope

The system represents a single airline operating flights between airports.
The airline has an administrative back office responsible for defining schedules. Customers can search
for flights, view seat availability, make bookings, and cancel bookings.
The implementation should support bookings for up to one year from the current date.

## Functional Requirements

### 1. Airport Management

Airports are assumed to already exist.
For this assignment:
Airports may be seeded into the database.
Airport CRUD is out of scope.
Each airport should have at least:
Airport Code (IATA)
Airport Name
City
Country

### 2. Aircraft Management

Aircraft may also be pre-configured.
Each aircraft contains:
Aircraft ID
Aircraft Type
Seat Configuration
Example:
Aircraft A320
Rows: 30
Seats:
A B C D E F
The seat map remains fixed for an aircraft.
Aircraft CRUD is out of scope.

### 3. Flight Schedule Management (Back Office)

The system must expose administrative APIs to create flight schedules.
A schedule contains:
Flight Number
Source Airport
Destination Airport
Departure Time
Arrival Time
Aircraft
Days of Operation
Example:
XY101
Dubai → London
Departs 09:30
Arrives 13:45
Operates:
Monday
Wednesday
Friday

### 4. Flight Instance Generation

Schedules are templates.
The booking engine must generate (or logically expose) bookable flight instances for 365 days from
the current date.
Example:
Schedule
XY101
Every Monday
Results in:
XY101
7 July
XY101
14 July
XY101
21 July
...
until one year ahead.
The implementation may choose one of the following approaches:
Materialize flight instances
Generate them dynamically
Hybrid approach
The design decision must be documented.

### 5. Flight Search

Customers must be able to search flights using:
Source Airport
Destination Airport
Travel Date
The system should return:
Flight Number
Departure Time
Arrival Time
Available Seats
Only flights operating on the requested date should be returned.
Sorting and filtering beyond this are out of scope.

### 6. Seat Map

Each flight exposes a seat map.
Each seat should have one of:
Available
Booked
Seat labels should follow aircraft configuration.

Example
1A
1B
1C
...
30F
Seat map should reflect real-time booking state.

### 7. Booking

Users should be able to:
Select a flight
View available seats
Select one or more seats
Create a booking
A successful booking returns:
Booking Reference (PNR or UUID)
Flight Number
Flight Date
Passenger Count
Assigned Seats
Booking Status
Passenger information may be minimal:
Name
No passenger validation is required.

### 8. Booking Constraints

The system should prevent:
Double booking
Multiple customers booking the same seat

Concurrent requests should maintain seat consistency.
Candidates should document their concurrency strategy.
Examples include:
Database locking
Optimistic locking
Pessimistic locking
Transactions
Versioning

### 9. Cancellation

Customers should cancel an existing booking.
Cancellation should:
Change booking status
Release reserved seats
Make seats available for future bookings
Refund processing is out of scope.

## Non-Functional Requirements

The system should demonstrate:
Clean architecture
Separation of concerns
RESTful API design
Transaction handling
Error handling
Validation
Logging
Testability
Performance optimizations are not required beyond reasonable implementation.

## Deliverables

Applicants must submit the following:

### 1. High-Level Design (HLD)

The HLD should include:
Overall architecture
Components
Service interactions
Request flow
Major design decisions
Assumptions
Concurrency strategy
Flight generation strategy
Architecture diagrams are encouraged.

### 2. Low-Level Design (LLD)

Describe:
Modules
Classes
Services
APIs
Validation
Error handling
Transaction boundaries
Sequence diagrams are optional.

### 3. Backend Implementation

Implement the backend using any programming language or framework of your choice.
Examples include:
Java
Go
Python
Node.js
Rust
The implementation should be executable with clear setup instructions.

### 4. Database Schema

Provide:
ER Diagram (preferred)
SQL schema or migration scripts
Table definitions
Primary keys
Foreign keys
Constraints
Indexes

### 5. README

The README must include:
Setup Instructions
Dependencies
Build
Run
Database initialization
Design Decisions
Explain major architectural choices.
Search Algorithm
Describe how:
Flights are identified
Schedules are matched
Flight instances are generated
Seat availability is computed
Booking Algorithm
Describe:
Seat validation
Seat locking strategy
Transaction flow
Booking creation
Concurrency handling
Cancellation Algorithm
Describe:
Booking lookup
Seat release
Transaction flow
Status updates
Assumptions
List any assumptions made.

## Evaluation Criteria

Candidates will be evaluated on:
Correctness of functionality
API design
Database design
Code organization
Scalability considerations
Concurrency handling
Documentation quality
Design decisions
Readability and maintainability
Testing approach

## Assumptions

Unless explicitly implemented otherwise, the following assumptions may be made:

Single airline only
One seat belongs to only one booking
One booking is for a single flight instance
Seat maps are fixed per aircraft
Flight schedules do not change after creation
Airports and aircraft are preloaded
Booking window extends up to one year from the current date
All timestamps should be stored in UTC
Time zone conversion is out of scope

## Expected Repository Structure

```
/
├── README.md
├── hld/
│   ├── architecture.pdf
│   └── diagrams/
├── lld/
│   └── design.md
├── src/
├── db/
│   ├── schema.sql
│   └── migrations/
├── tests/
└── docker-compose.yml (optional)
```

---

## Supplementary instruction (from the hiring team, outside the document)

> A working Service, Backend only along with the docs preferable in a github repo.
> No need to code for the black boxes explained in the assignment, you can leave those
> calls as a static response.

---

## Clarifications requested before implementation (from the hiring team, outside the document)

> Seven non-functional questions were put to the interviewer before any design work was
> committed, because each one could change a decision rather than merely a detail. The
> replies are reproduced **verbatim** below and carry the same authority as the brief
> itself. Where a reply and an inference from the brief disagree, **the reply wins**.

**Please find the clarifications below:**

> **Should the system be designed to run as multiple application instances behind a load
> balancer, or a single instance?**
> => Design for multiple instances, but we'll bring up a single instance while checking

> **What scale should I design for — approximate daily active users and number of active
> flight schedules?**
> => more focus is to be done on the functionality, you can assume moderate scale (10k
> DAU, 500 flight schedules)

> **What is the expected ratio of search traffic to booking traffic?**
> => 25:1

> **Can flight search results be a few seconds stale, or must availability be exact at
> read time?**
> => If they are late, how does the user get informed if there is a change in the state
> when he proceeds for a booking, if that is handled, the results can be stale

> **Are there latency targets for search and booking?**
> => Search - 1second with a provision to serve lazy loading if needed, booking -
> ultimate stability is key, the time can change based on load and concurrent attempts

> **Read:write ratio — search dominating bookings by roughly?**
> => Same as 3

> **Consistency split — search may be a few seconds stale, but booking must be strongly
> consistent with zero double-booking**
> => yes

