# Appointment Booking — Design Specification

## 1. Service boundary

This slice solves exactly one problem: **prevent a provider from being double-booked for overlapping active appointments, and let a booking be safely cancelled.** Everything else a real scheduling product would need is explicitly out of scope:

- No authentication/authorization — anyone reaching the service can book or cancel for any provider.
- No notifications (email/SMS/webhooks on booking or cancellation).
- No recurring availability or provider working-hours model — every booking is a one-off interval.
- No calendar integration.
- No database/persistence — state lives in memory for the life of the process.
- No deployment config (no Docker, no CI, no environment profiles).
- No frontend.

The REST API, in-memory store, and the two operations (`book`, `cancel`) plus one read convenience (`GET` by provider) are the entire surface.

## 2. Data model

```java
record Appointment(UUID id, String providerId, String customerId,
                    Instant startTime, Instant endTime, AppointmentStatus status)
```

- **`id`** — server-generated `UUID`, never client-supplied, so there's no way for two clients to collide on an id or to overwrite another appointment by guessing an id.
- **`providerId`** — the conflict boundary. All overlap checking is scoped to appointments sharing a `providerId`; different providers never interact.
- **`customerId`** — a **deliberate requirement, not scope creep.** The acceptance criteria call for rejecting requests with "missing identifiers" (plural) — not "missing identifier." A booking that only names a provider isn't a meaningful appointment record: there's no one to ask "is there a reason this appointment was made," and cancellation / future audit of "whose slot was this" has nothing to resolve against. This was confirmed explicitly rather than assumed, and is validated identically to `providerId` (required, non-blank, checked before any lock is taken).
- **`startTime` / `endTime`** — `java.time.Instant`. `Instant` has no offset concept, so any accepted timestamp is already normalized to UTC by the type itself — "UTC-only" is enforced by type choice, not by extra runtime logic.
- **`status`** — `ACTIVE` or `CANCELLED`.

`Appointment` is an immutable Java `record`. Cancellation never mutates a field in place — it produces a new instance via `withStatus(...)` and the old reference is replaced in the store under the provider's lock. This means the only place visibility of a status change can go wrong is the locking around the replace, not some scattered mutable field — one invariant to get right instead of many.

## 3. API design

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/appointments` | book |
| `POST` | `/appointments/{id}/cancel` | cancel |
| `GET` | `/appointments/{providerId}` | manual-verification convenience only — not exercised by the automated test suite |

**`POST /appointments`**
Request: `{ "providerId": "p1", "customerId": "c1", "startTime": "2026-11-01T10:00:00Z", "endTime": "2026-11-01T11:00:00Z" }`

| Status | When | Body |
|---|---|---|
| `201` | booked | the created `Appointment` (id, providerId, customerId, startTime, endTime, status=ACTIVE) |
| `400` | blank providerId/customerId, missing/malformed/non-`Z` timestamp, non-positive duration, or unparseable JSON body | `{"error":"INVALID_REQUEST","message":"..."}` |
| `409` | overlaps an active appointment for the same provider | `{"error":"CONFLICT","message":"... (existing appointment: <start> to <end>)"}` |

**`POST /appointments/{id}/cancel`**

| Status | When | Body |
|---|---|---|
| `200` | id existed and was `ACTIVE` | `{"result":"CANCELLED","appointment":{...status:"CANCELLED"}}` |
| `200` | id existed and was already `CANCELLED` | `{"result":"ALREADY_CANCELLED","appointment":{...}}` |
| `404` | `{id}` is a syntactically valid id that was never booked | `{"error":"NOT_FOUND","message":"..."}` |
| `400` | `{id}` isn't even a valid UUID | `{"error":"INVALID_REQUEST","message":"..."}` — rejected before any lookup happens |

**Why three cancel outcomes, not a boolean:** `NOT_FOUND`, `ALREADY_CANCELLED`, and `CANCELLED` are three genuinely different situations a caller needs to branch on:
- `NOT_FOUND` is a real client mistake (typo'd id) — worth a `404`, an actual error status.
- `ALREADY_CANCELLED` is an idempotent retry — the caller's intent ("make sure this is cancelled") already holds, so it isn't an error; it gets `200`.
- `CANCELLED` is the fresh success path — also `200`, but distinguishable from the retry case.

Collapsing any two of these into one shape would force a caller to string-match an error message to figure out which case actually happened. Instead there's a dedicated `CancellationOutcome` enum (`NOT_FOUND`/`ALREADY_CANCELLED`/`CANCELLED`) and two response DTOs — `ErrorResponse` for the `404` case, `CancellationResponse{result, appointment}` for the two `200` cases — so the distinction is structural, not textual.

## 4. The booking invariant

For the same `providerId`, two intervals `[start1, end1)` and `[start2, end2)` **overlap** iff:

```
start1 < end2  AND  start2 < end1
```

This is the standard half-open-interval overlap test. Both comparisons are strict (`<`, never `<=`), so when one appointment's `end` exactly equals the next one's `start`, the comparison on that side is `false` — the two intervals are adjacent, not overlapping, and the booking is allowed. This is exactly the semantics you want for scheduling: an appointment "owns" every instant from `start` up to, but not including, `end`, so the instant `end` is free for the next appointment to claim.

Only `ACTIVE` appointments for that provider are checked; `CANCELLED` ones are excluded from the scan entirely, which is what makes cancellation actually free the slot rather than just hide the record.

## 5. Concurrency approach

Each `providerId` gets its own lock object, created lazily:

```java
providerLocks.computeIfAbsent(providerId, id -> new Object())
```

`ConcurrentHashMap.computeIfAbsent` is atomic — two threads racing to create "the first lock for provider X" always converge on the same `Object` instance, so there's no window where two threads could each believe they created the canonical lock.

- `book()` acquires that lock, then does the overlap scan and the insert as one atomic unit inside the `synchronized` block — no other thread can observe or mutate that provider's list in between the scan and the insert.
- `cancel()` acquires the **same** lock, keyed by the same `providerId`, before reading or mutating status. This is the one correctness-critical detail: if cancel used no lock, or a different one, a concurrently running `book()` could read a half-transitioned appointment and either wrongly reject a now-free slot or wrongly accept a still-active conflict. Sharing the lock turns a `book`/`cancel` race on the same appointment into a strict ordering — whichever thread acquires the lock first completes entirely before the other one starts — which is exactly what the race test (`cancelBookRace_repeatedly_alwaysReachesValidEndState`, run 200 times with fresh state) exists to verify.

**Tradeoff: per-provider locks vs. one global lock.** A single global lock would also be correct — trivially, since it serializes everything — but it throws away concurrency between unrelated providers for no reason: booking provider A and provider B at the same instant would serialize even though they can never conflict with each other. Per-provider locking gives the identical correctness guarantee (overlap can only ever exist *within* a provider, so there's nothing a global lock would catch that a per-provider lock wouldn't) while letting unrelated providers proceed fully in parallel. The cost is a slightly larger lock-management surface — a lock map instead of a single field — which is the accepted tradeoff for a service whose whole point is many providers being booked independently of each other.

## 6. Persistence

Explicitly in-memory only, as scoped from the start — no database, no file-backed store, no durability. All state lives in three `ConcurrentHashMap`s inside `BookingService` and is lost on restart.

This is acceptable here because the assessment doesn't call for restart-survival, multi-instance deployment, or transactional guarantees across a real datastore — those are different, substantially larger problems (how do you do an atomic "check overlap, then insert" against a shared database under concurrent writers?) that this bounded slice was never asked to solve.

## 7. Key assumptions

- **UTC timestamps only, strictly.** Only ISO-8601 instants ending in `Z` are accepted. A parseable-but-offset timestamp like `2026-11-01T10:00:00+05:00` is rejected as invalid input rather than silently converted to its UTC equivalent — "UTC timestamps only" was read as a constraint on the wire format, not just the internal representation.
- **No maximum appointment duration.** The only duration rule is `endTime > startTime`; a one-year appointment is accepted.
- **No "now"-based validation.** A booking in the past is accepted — this is a pure interval-overlap service, not a scheduling policy engine, and avoiding a clock dependency also keeps the tests deterministic.
- **`providerId` / `customerId` are unconstrained non-blank strings.** No format, length, or existence validation against any external directory — there is no provider/customer registry in this slice to validate against.
