# ADR-0004: Message broker - RabbitMQ (proposed)

- Status: Proposed - awaiting confirmation (Phase 0 decision list)
- Date: 2026-11-18

## Context
Needs: outbox relay, notifications, reconciliation and settlement work queues, DLQs.
Volumes for initial MFI/SACCO tenants are modest. Windows Server is a supported target.

## Decision (proposed)
RabbitMQ with quorum queues. Reasons: supported on Windows Server, simpler operations
for dedicated on-prem installs, per-message acknowledgements and DLX fit work-queue use.
Kafka on Windows is not recommended for production (brief 11.1).

## Consequences
- Event replay comes from the outbox table (source of truth), not from broker retention.
- If analytics streaming at scale is needed later, add Kafka on Linux for that use only.
