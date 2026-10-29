# Assumptions & Booking Invariant

- **Timestamps are UTC-only.** Only ISO-8601 instants ending in `Z` are accepted (e.g. `2026-11-01T10:00:00Z`). An otherwise-valid offset timestamp like `2026-11-01T10:00:00+05:00` is rejected as invalid input, not silently converted.
- **Overlap invariant (same provider only):** two intervals `[start1, end1)` and `[start2, end2)` conflict iff `start1 < end2 AND start2 < end1`. Both comparisons are strict, so adjacent appointments (`end1 == start2`) are explicitly allowed, not treated as a conflict.
- **Cancellation is idempotent and three-valued, not boolean.** Cancelling returns `CANCELLED` (fresh cancellation), `ALREADY_CANCELLED` (idempotent retry), or `NOT_FOUND` (unknown id) — three distinct, structurally-typed outcomes rather than a single generic result.
- **`customerId` is a required identifier, not just `providerId`.** This was a deliberate decision (see design-spec.md §2), not scope creep — the brief's "missing identifiers" (plural) implies more than one is validated.
- **No maximum appointment duration and no "now"-based validation.** The only time rule enforced is `endTime > startTime`. A booking in the past, or one lasting a year, is accepted.
- **`providerId` / `customerId` are unconstrained non-blank strings** — no format, length, or cross-reference validation against any external system.
