# ADR-0001: Modular monolith core + separate switch and integration deployables

- Status: Accepted (Phase 0, 2026-11-18)
- Deciders: Technopick architecture (pending leadership confirmation)

## Context
The brief asks for a modular monolith for the banking domain and warns against premature
microservices, but the financial switch and the Camel integration runtime have different
availability, latency, network-zone, scaling and certification needs.

## Decision
Four deployables, one repository, one build:

| Deployable | Why separate |
| --- | --- |
| `core-banking` | Single owner of balances, holds and postings; modules enforced by Spring Modulith + ArchUnit |
| `switch` (jPOS) | Long-lived TCP links, scheme timeouts in seconds, HSM access, PCI zone, scheme certification of a fixed build |
| `integration` (Camel) | Egress to many providers, different failure modes, independent restarts without touching links |
| `worker` | Batch/EOD load isolated from interactive traffic; same core code, different process |

The switch and integration runtimes never post to the GL or compute balances; they call the
core through versioned internal APIs (authorization port, application API).

## Consequences
- Three network zones and mTLS between deployables.
- The canonical model is a shared library (`shared/canonical-model`) with no framework deps.
- A switch release can be certified without re-certifying unrelated core changes.
