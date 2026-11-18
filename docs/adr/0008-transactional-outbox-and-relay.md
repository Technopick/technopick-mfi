# ADR-0008: Transactional outbox and Camel relay

- Status: Accepted (increment 7, 2026-11-18)

## Decision
- Core banking writes integration events to `messaging.outbox_event` in the same database
  transaction as the business change (V7). No dual writes to a broker from business code.
- Event ids are deterministic (tenant + type + aggregate), so a replayed business call cannot
  create a second event.
- The Camel integration runtime relays events (route `outbox-relay` -> `outbox-publish`):
  claim a batch `FOR UPDATE SKIP LOCKED`, publish to the `mfi.events` topic exchange with
  publisher confirms, mark published in the same transaction. Failure rolls the batch back and
  counts an attempt in a separate transaction; the next tick retries.
- Delivery is at-least-once; consumers de-duplicate on header `mfiEventId` (inbox pattern).
  Routing key `<EventType>.<tenantId>`. No global ordering guarantee.
- Roles: `mfi_app` may insert/read its tenant's events; `mfi_relay` reads all tenants' events
  and may update only `published_at` / `publish_attempts`; nobody may edit or delete events.
- Broker: RabbitMQ per ADR-0004 (still "proposed"). Choosing Kafka changes only the
  `outbox-publish` transport route.

## Verified (CI, PostgreSQL 17 + RabbitMQ 4.1 via Testcontainers)
Events from two tenants published exactly once; already-published events skipped; with the
exchange deleted the publish is not confirmed, the event stays unpublished with its attempt
counted, and it is delivered once the exchange is back; the relay role cannot rewrite or delete
events. Core's lifecycle test asserts exactly one AuthorizationCompleted and one
AuthorizationReversed event despite replayed calls.
