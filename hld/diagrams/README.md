# Diagrams

The Mermaid blocks in the design documents are the source. The SVG files in this
folder are rendered copies for printing, for full-size viewing and for readers without
a Mermaid renderer. To change a diagram, edit its Markdown block first. Then render that
block again, replace the matching SVG and, if an HLD diagram changed, reprint the HLD PDF.

| ID | Diagram | Type | Source section | Rendered copy |
|---|---|---|---|---|
| HLD-1 | Components | Flowchart | [HLD: overall architecture](../architecture.md#overall-architecture-and-components) | [hld-1-components.svg](hld-1-components.svg) |
| HLD-2 | Deployment | Flowchart | [HLD: overall architecture](../architecture.md#overall-architecture-and-components) | [hld-2-deployment.svg](hld-2-deployment.svg) |
| HLD-3 | Request lifecycle | Flowchart | [HLD: service interactions](../architecture.md#service-interactions-and-request-flows) | [hld-3-request-lifecycle.svg](hld-3-request-lifecycle.svg) |
| HLD-4 | Hybrid generation | Flowchart | [HLD: flight generation](../architecture.md#flight-generation-strategy) | [hld-4-flight-generation.svg](hld-4-flight-generation.svg) |
| HLD-5 | Two replicas race for one seat | Sequence | [HLD: concurrency](../architecture.md#concurrency-strategy) | [hld-5-seat-race.svg](hld-5-seat-race.svg) |
| LLD-1a | Public contracts and implementations | Class | [LLD: class diagrams](../../lld/design.md#class-diagrams) | [lld-1a-contracts.svg](lld-1a-contracts.svg) |
| LLD-1b | Internal collaborators | Class | [LLD: class diagrams](../../lld/design.md#class-diagrams) | [lld-1b-collaborators.svg](lld-1b-collaborators.svg) |
| LLD-2 | Repositories and JPA entities | Class | [LLD: class diagrams](../../lld/design.md#class-diagrams) | [lld-2-persistence.svg](lld-2-persistence.svg) |
| LLD-3 | Transport records, application models and mapping | Class | [LLD: class diagrams](../../lld/design.md#class-diagrams) | [lld-3-dto-model.svg](lld-3-dto-model.svg) |
| LLD-4 | Create a schedule (FR1) | Sequence | [LLD: algorithms](../../lld/design.md#algorithms-and-sequence-diagrams) | [lld-4-create-schedule.svg](lld-4-create-schedule.svg) |
| LLD-5 | Search and seat map (FR4, FR5) | Sequence | [LLD: algorithms](../../lld/design.md#algorithms-and-sequence-diagrams) | [lld-5-search-seat-map.svg](lld-5-search-seat-map.svg) |
| LLD-6 | Book seats: success and conflict (FR6, FR7, FR9) | Sequence | [LLD: algorithms](../../lld/design.md#algorithms-and-sequence-diagrams) | [lld-6-booking.svg](lld-6-booking.svg) |
| LLD-7 | Cancel a booking (FR8) | Sequence | [LLD: algorithms](../../lld/design.md#algorithms-and-sequence-diagrams) | [lld-7-cancellation.svg](lld-7-cancellation.svg) |
| LLD-8a | Booking status | State | [LLD: lifecycle states](../../lld/design.md#lifecycle-states) | [lld-8a-booking-states.svg](lld-8a-booking-states.svg) |
| LLD-8b | Seat allocation status | State | [LLD: lifecycle states](../../lld/design.md#lifecycle-states) | [lld-8b-seat-states.svg](lld-8b-seat-states.svg) |
| ER | Entity relationships | ER | [Database ER diagram](../../db/er-diagram.md) | [db-er-diagram.svg](db-er-diagram.svg) |

## Rendering

- Mermaid 11.15.0, the copy bundled with the IntelliJ IDEA 2026.2.3 Mermaid plugin, ran
  in headless Google Chrome. No online rendering service was used.
- Settings: default theme, `securityLevel: strict`, `htmlLabels: false` (plain SVG text,
  no `foreignObject`), `flowchart.wrappingWidth: 280` and a white background.
- GitHub and the IntelliJ Markdown preview render the same source with HTML labels, so
  line wrapping can differ slightly from these files.
- Mermaid draws class, state and ER box borders with random curve control points.
  Re-rendering those diagrams changes the file bytes, not the picture.
- GitHub scales wide diagrams (HLD-1, HLD-5, LLD-2 and LLD-4 to LLD-7) to its page
  column. Open the SVG for full size.
- IntelliJ's SVG 1.1 schema check flags Mermaid's ARIA `role` and `name` attributes.
  The files are valid for browsers and GitHub, which display them normally.

## HLD PDF

[`../architecture.pdf`](../architecture.pdf) is `architecture.md` printed to A4 with
headless Chrome. The five HLD diagrams are embedded as vector SVG; HLD-1, HLD-2 and
HLD-5 are on landscape pages. Relative links point to the `main` branch on GitHub.
