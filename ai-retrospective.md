# AI Retrospective — Appointment Booking Assessment

**This file is the complete AI prompt-and-use record for this assessment submission.** Every major prompt, Claude was given across the whole collaboration is quoted verbatim below, in order, each followed by what Claude actually did, whether it was accepted as-is or corrected, which files it touched, and the concrete evidence used to verify it. This is written from my own side, honestly, including the parts that went wrong.

---

## 1. Design discussion

**Prompt (verbatim):**
```
I'm building a bounded appointment-booking feature for an engineering assessment. No starter project is supplied, so I'm building a minimal standalone service.

OBJECTIVE
Prevent a provider from being booked for overlapping active appointments. Implement conflict-safe booking and cancellation.

STACK DECISIONS (already made, don't revisit)
- Java + Spring Boot, Maven
- Small REST API (controller + service + in-memory store), no frontend, no database
- UTC timestamps only

ACCEPTANCE CRITERIA
1. A valid appointment can be booked for a provider and a positive-duration time interval.
2. Booking is rejected if it overlaps another active appointment for the same provider. Different providers never conflict.
3. Adjacent appointments are allowed (one ends exactly when the next begins — not an overlap).
4. Cancelling frees that time slot. Cancelling a missing or already-cancelled booking returns a clear, non-exceptional result (not a 500).
5. Invalid timestamps, non-positive durations, and missing identifiers are rejected without mutating stored state.
6. Needs tests for: successful booking, same-provider overlap, adjacency, different providers, cancellation, invalid input, and a focused concurrency test (concurrent booking attempts for the same slot should not both succeed).

SCOPE BOUNDARIES — do NOT include
- No auth, no notifications, no recurring availability, no calendar integration, no deployment config, no database.
- No frontend.
- Don't overbuild — this is one bounded slice, not a full scheduling product.

My own starting proposal, push back if you see a problem
- Invariant: for same provider, [start1,end1) and [start2,end2) overlap iff start1 < end2 AND start2 < end1. This correctly allows adjacency (end1 == start2).
- Data model: Appointment { id (UUID), providerId (String), startTime (Instant, UTC), endTime (Instant, UTC), status (ACTIVE/CANCELLED) }.
- API: POST /appointments to book; POST /appointments/{id}/cancel to cancel. 201 on booking success, 409 on overlap conflict, 400 on invalid input, 404 if cancelling an id that never existed, 200 with a "already cancelled" result if cancelling an already-cancelled one (not an error).
- Concurrency: in-memory store keyed by providerId, with a lock scoped per provider (e.g., synchronized block or ReentrantLock per providerId) so overlap-check-then-insert is atomic for that provider; different providers proceed independently.

WHAT I WANT FROM YOU RIGHT NOW
Review my proposal above. Confirm or correct the invariant, model, API, and concurrency approach, and call out any assumptions I'm missing (timestamp format, id format, max duration, clock source). Don't write code yet - just the design, so we agree before scaffolding.
```

**What happened:** No code existed yet. I confirmed the overlap invariant as correct, confirmed the data model with one addition (server must generate `id`, never client-supplied), confirmed the API/status codes, and caught a real gap not in the original proposal: **cancel must share the exact same per-provider lock as book**, not just lock on insert — because cancel mutates status that book's overlap scan reads. I also flagged that the data model had only one identifier (`providerId`) while acceptance criterion 5 said "missing **identifiers**" (plural), raising whether a second identifier (e.g. `customerId`) was intended, without unilaterally adding anything since no code existed yet.

**Outcome:** Accepted as-is. The lock-sharing correction and the `customerId` question both became durable decisions confirmed in the next prompt.

**Files or decisions informed:** No files yet — this shaped the design that `BookingService.java` and `Appointment.java` were later built against.

**Verification evidence:** None applicable yet (pure design discussion, no code).

---

## 2. Scaffold + domain model/service

**Prompt 1 (verbatim) — locked decisions + scaffold request:**
```
Continuing from the design we agreed on. Final decisions locked in:
- customerId will also be present
- Keep per-provider locking (not a global lock). Use ConcurrentHashMap<String, Object> with computeIfAbsent for lock creation. Cancel must acquire the SAME per-provider lock as booking before mutating status.
- Server generates appointment id (UUID), never client-supplied.
- Store: Map<String providerId, List<Appointment>> for per-provider overlap scan, PLUS Map<UUID, Appointment> as a global index for O(1) cancel-by-id lookup.
- Timestamps: ISO-8601 instants ending in "Z" only (java.time.Instant), parsed via Instant.parse(). Reject anything else (including offset timestamps like +05:00) as invalid input. Make sure timestamps are in UTC only
- No max duration cap, no validation against current time -> pure overlap-based validation only.
- Validate input shape (non-blank providerId, parseable start/end, end > start) before acquiring any lock or touching the store. All validations should be done before booking
- Overlap invariant: for same provider, [start1,end1) and [start2,end2) overlap iff start1 < end2 AND start2 < end1 (adjacency allowed).
- Add GET /appointments/{providerId} later.

Now, just scaffold a Maven Spring Boot project (standard layout: src/main/java, src/test/java, pom.xml with spring-boot-starter-web and spring-boot-starter-test only - no database/JPA dependencies).
Don't create other files, we will work step by step on it
```

**Prompt 2 (verbatim) — domain model + service request (sent after the scaffold was done):**
```
NOW DO THIS
1. Create the Appointment domain class (id, providerId, startTime, endTime, status enum ACTIVE/CANCELLED).
2. Create the BookingService with:
   - book(providerId, startTime, endTime) -> validates input, checks overlap against that provider's active appointments under that provider's lock, inserts if no conflict, returns the created Appointment or throws/returns a typed conflict result.
   - cancel(appointmentId) -> looks up the appointment to find its providerId, acquires THAT provider's lock, then marks it cancelled if active, returns a typed result distinguishing "not found" vs "already cancelled" vs "cancelled successfully".
3. Don't add the REST controller yet -just the domain model and service, so this piece is reviewable on its own.

Show me the full file contents for each class you create.
```

**What happened:** Scaffolded the Maven project (Spring Boot 3.3.4, Java 17, `spring-boot-starter-web` + `spring-boot-starter-test` only), then built `Appointment` as an immutable record and `BookingService` with per-provider `synchronized` locking. Note: prompt 2's literal field list for `Appointment` (`id, providerId, startTime, endTime, status`) and `book()`'s parameter list do **not** include `customerId`, even though prompt 1 had just locked it in. I added it anyway, flagging it explicitly before showing the code: *"Before I write these, one note: your field list for `Appointment` in this message doesn't include `customerId`, but it was part of our locked decisions last time... I kept it in — flag me if that was dropped intentionally."* This is the first of two `customerId` deviations — see section 5.

**Outcome:** Mostly accepted as-is (the lock design, record immutability, overlap check). The `customerId` inclusion was a deviation from this specific prompt's literal text, flagged but not asked permission for first.

**Files or decisions informed:** `pom.xml`, `src/main/java/com/appointment/booking/AppointmentBookingApplication.java`, `src/main/java/com/appointment/booking/domain/Appointment.java`, `src/main/java/com/appointment/booking/domain/AppointmentStatus.java`, `src/main/java/com/appointment/booking/service/BookingService.java`, plus the exception/result types (`CancellationOutcome.java`, `CancellationResult.java`, `AppointmentConflictException.java`, `InvalidAppointmentRequestException.java`).

**Verification evidence:** `mvn -q -DskipTests compile` run after the scaffold (clean, no output = success) and again after `BookingService`/`Appointment` were written (clean compile, confirmed via the same command with no error output).

---

## 3. Controller + DTOs + validation

**Prompt (verbatim):**
```
The Appointment domain class and BookingService (book/cancel with per-provider locking) already exist from the previous step - build on them, don't recreate them.

NOW DO THIS
1. Create request/response DTOs:
   - BookingRequest { providerId (String), startTime (String, ISO-8601 "Z"), endTime (String, ISO-8601 "Z") }
   - A response representation of Appointment for JSON output (id, providerId, startTime, endTime, status).
2. Create AppointmentController with:
   - POST /appointments -> calls BookingService.book(). Returns 201 + created appointment on success, 409 + a clear conflict message on overlap, 400 + a clear message on invalid input (bad/non-"Z" timestamp, non-positive duration, blank providerId).
   - POST /appointments/{id}/cancel -> calls BookingService.cancel(). Returns 200 with a "cancelled" result if it was active and is now cancelled, 200 with an "already cancelled" result if it was already cancelled, 404 if the id doesn't exist. These must be distinguishable response bodies, not one generic shape.
   - GET /appointments/{providerId} -> returns that provider's appointments (for manual verification convenience only, not used by the test suite).
3. Add validation and error handling so that NO invalid request ever reaches the service layer's lock/store logic — validate shape in the controller (or a dedicated validator called first) before BookingService is invoked at all.
4. Use a consistent error response shape, e.g. { error: string, message: string }, for all 400/404/409 cases.

Show me the full file contents for each class you create or modify.
```

**What happened:** Again, the literal `BookingRequest` field list omits `customerId`. This time I didn't just note it and proceed — I stopped and asked via a blocking question before writing any code (see section 5). After the answer, I built `BookingRequest`, `AppointmentResponse`, `CancellationResponse`, `ErrorResponse`, and `AppointmentController`. I also added a `findByProvider()` method to `BookingService` that didn't exist before, since the `GET` endpoint needed it — a genuine extension, not a rewrite of `book()`/`cancel()`.

**Outcome:** Accepted as-is, including the design choices beyond the literal spec: `endTime.isAfter(startTime)` re-checked in the controller even though the service also checks it (intentional duplication so no invalid request reaches the lock), and treating a malformed (non-UUID) cancel id as `400` rather than `404`.

**Files or decisions informed:** `BookingService.java` (added `findByProvider`), `web/dto/BookingRequest.java`, `web/dto/AppointmentResponse.java`, `web/dto/CancellationResponse.java`, `web/dto/ErrorResponse.java`, `web/AppointmentController.java`.

**Verification evidence:** `mvn -q -DskipTests compile` after the DTOs/controller were written — clean, no errors.

---

## 4. Tests

**Prompt 1 (verbatim) — the main test-writing request:**
```
Write JUnit 5 tests covering all of the following as separate test methods with clear names:

Service-level tests (test BookingService directly, not through HTTP):
1. Successful booking - valid providerId/startTime/endTime returns an ACTIVE appointment with a generated id.
2. Same-provider overlap - booking a second appointment that overlaps an existing active one for the same provider is rejected.
3. Adjacency - booking an appointment that starts exactly when another ends (same provider) succeeds (not treated as overlap).
4. Different providers - two overlapping time ranges for different providers both succeed.
5. Cancellation frees the slot - cancel an active appointment, then successfully book the same slot again.
6. Cancel a missing id — returns a clear "not found" result, does not throw, does not mutate any state.
7. Cancel an already-cancelled appointment - returns a clear "already cancelled" result, distinguishable from "not found" and from a fresh cancellation.
8. Invalid input, each as its own test, asserting no state is mutated (store stays empty/unchanged after the rejected call):
   - malformed timestamp string
   - non-"Z" offset timestamp (e.g. "+05:00")
   - non-positive duration (endTime <= startTime)
   - blank/missing providerId
9. Exact duplicate booking - identical start/end for the same provider (not adjacent) is rejected as overlap, same as case 2.
10. Zero-duration appointment (start == end) - rejected as non-positive duration, not accepted as a valid zero-length booking.

Add this test cases, also if anything missed then add that too wrt service level tests only.
```

**What I flagged before writing:** `BookingService.book()` takes `Instant`, not `String` — "malformed timestamp string" and "non-Z offset timestamp" literally cannot be exercised against the service, since there's no string parameter to malform. I substituted `null` `startTime`/`endTime` tests as the service's actual boundary, and said so explicitly rather than silently writing something that didn't match the ask. I also added a `blankCustomerId` test (parallel to `blankProviderId`, not in the list) and a concurrency test (`book_concurrentAttemptsSameSlot_onlyOneSucceeds`) that was in the *original* acceptance criteria but missing from this specific list.

**Prompt 2 (verbatim) — verification + controller-level gap:**
```
Check these 2 test cases also

Exact duplicate booking (identical start/end for same provider, not adjacent) - must be rejected as overlap, not accepted.
Zero-duration appointment (start == end) - must be rejected as non-positive duration, not treated as a valid zero-length booking.

Test malformed timestamp in controller
```

**What happened:** I re-read `book_exactDuplicateSlot_rejectedAsOverlap` and `book_zeroDuration_rejectedAsNonPositiveDuration_noStateMutated` and confirmed both were already correct — no fix was needed, just verification. For "malformed timestamp in controller," I wrote a new `AppointmentControllerTest` using `@WebMvcTest(AppointmentController.class)` + `@MockBean BookingService`. **This failed outright**: `mvn test` returned `Tests run: 3, Failures: 0, Errors: 3` with `org.mockito.exceptions.base.MockitoException: Mockito cannot mock this class` — a JDK 25 / ByteBuddy incompatibility with inline class mocking, confirmed by reading `target/surefire-reports/com.appointment.booking.web.AppointmentControllerTest.txt` directly. I rewrote the test using `@SpringBootTest` + the real `BookingService` bean instead of a mock, proving "service never invoked" by asserting no appointment was stored rather than verifying a mock interaction. Re-ran `mvn test`: `Tests run: 3, Failures: 0, Errors: 0`.

**Prompt 3 (verbatim) — concurrency tests:**
```
Concurrency test, add them.
1. Race between cancel and book on the same slot: an active appointment A exists for provider P at 10:00-11:00. Launch two threads simultaneously - one calls cancel(A.id), the other calls book(P, 10:00, 11:00) for the same slot. Run this scenario in a loop (at least 100 iterations, fresh service/appointment state each iteration) and assert that after both threads complete, the end state is always one of the two valid outcomes (cancel-then-book-succeeds, or book-rejected-then-cancel-succeeds) and NEVER an invalid state (e.g., two active appointments occupying the same slot, or a thrown/uncaught exception from either thread).
2. A second concurrency test: two threads simultaneously attempt to book the exact same new slot for the same provider (no prior appointment). Run in a loop (at least 100 iterations). Assert exactly one booking succeeds and the other is rejected as a conflict, every iteration.
```

**What happened:** Added `cancelBookRace_repeatedly_alwaysReachesValidEndState` and `concurrentBookingSameNewSlot_repeatedly_exactlyOneSucceedsEveryIteration`, both looping 200 iterations (above the 100 minimum) with a fresh `BookingService` per iteration, kept rather than replaced the pre-existing 20-thread single-run concurrency test since it stresses a different angle (breadth of contention vs. repeated narrow-race timing).

**Outcome:** First `AppointmentControllerTest` attempt (Mockito) was wrong and failed the build — a real, self-caught failure, not a user correction, but still a wrong technical choice I presented by writing it. Everything else in this section (test substitutions, the two verification checks, the concurrency tests) was accepted as-is.

**Files or decisions informed:** `src/test/java/com/appointment/booking/service/BookingServiceTest.java` (grew from 15 → 17 methods), `src/test/java/com/appointment/booking/web/AppointmentControllerTest.java` (written, failed, rewritten).

**Verification evidence:**
- First full test suite run (15 service + 1 smoke): `mvn test` → `Tests run: 16, Failures: 0, Errors: 0, BUILD SUCCESS`.
- `AppointmentControllerTest` first attempt: `mvn test` → `Tests run: 19, Errors: 3`; root cause confirmed by reading `target/surefire-reports/com.appointment.booking.web.AppointmentControllerTest.txt`, which showed the `MockitoException` stack trace.
- `AppointmentControllerTest` rewrite: `mvn test` → `Tests run: 19, Failures: 0, Errors: 0, BUILD SUCCESS`.
- After concurrency tests added: `mvn test` → `Tests run: 21, Failures: 0, Errors: 0, BUILD SUCCESS`, then re-ran `mvn -q -Dtest=BookingServiceTest test` three additional times in a row specifically to check for timing-related flakiness in the race tests — all three silent (no output under `-q` means no failures).

---

## 5. The `customerId` requirement — raised, then deviated from twice, then explicitly confirmed

This is **not** a case of the user issuing a "remove `customerId`" instruction that was later reversed — no such instruction exists in this conversation. What actually happened, precisely:

1. Design discussion (section 1): I raised `customerId` as an open question; original proposal had no second identifier.
2. User's next prompt (section 2, Prompt 1) locked it in: `- customerId will also be present`.
3. User's *following* prompt (section 2, Prompt 2) listed `Appointment`'s fields as `(id, providerId, startTime, endTime, status enum ACTIVE/CANCELLED)` — **no `customerId`**. I added it anyway and flagged it in prose, not as a question.
4. User's *next* prompt (section 3) listed `BookingRequest { providerId (String), startTime (String...), endTime (String...) }` — **again no `customerId`**. This time I stopped and used a blocking question instead of deciding myself:

   **My question (verbatim):** *"Should the booking API include customerId, matching the earlier locked decision, or has that decision been dropped?"*
   **Options offered (verbatim):** *"Keep customerId (Recommended) — BookingRequest gets a customerId field, controller passes it through to BookingService.book() as already implemented. Matches the earlier locked decision."* / *"Drop customerId entirely — Remove customerId from Appointment, BookingService.book(), and all DTOs — provider-only model, superseding the earlier decision."*
   **User's answer (verbatim):** *"Keep customerId (Recommended)"*

**Outcome:** The field stayed in the final design, confirmed explicitly by the user — but only after I'd already deviated from the literal text of two separate instructions to get there. Flagging a deviation after making it is not the same as it having been requested.

**Files or decisions informed:** `domain/Appointment.java`, `service/BookingService.java` (`book()` signature), `web/dto/BookingRequest.java`, `web/dto/AppointmentResponse.java`.

**Verification evidence:** No test or build check applies to a design decision; the only "evidence" is the user's own explicit answer to the question, recorded above verbatim.

---

## 6. Thunder Client collection — two real defects, both now understood

**Prompt 1 (verbatim) — original ask, interrupted mid-delivery then completed with an addition:**
```
Give me a set of curl commands to manually exercise the running app (assume it's started on localhost:8080 via `mvn spring-boot:run`). Cover these scenarios, each as its own labeled curl command with the expected response (status code + body):

1. Book a valid appointment for provider "p1", e.g. 2026-11-01T10:00:00Z to 2026-11-01T11:00:00Z.
2. Book a second appointment for the same provider "p1" that overlaps the first - expect 409.
3. Book an adjacent appointment for "p1" starting exactly at 11:00:00Z (the first one's end time) - expect success.
4. Book an overlapping time range for a different provider "p2" - expect success (different providers don't conflict).
5. Cancel the first appointment (use its real id from step 1's response) - expect 200 with a "cancelled" result.
6. Cancel that same appointment id again - expect 200 with an "already cancelled" result, distinguishable from step 5's body.
7. Cancel a random/made-up UUID that was never booked - expect 404.
8. Book with a blank providerId - expect 400.
9. GET /appointments/p1 - show the current state of provider p1's appointments after the above.

Format each as a ready-to-run curl command with -i to show status codes.
If any curl for a test case is misisng add that too
```
[The user's message was interrupted at this point, then completed with:]
```
Just an addition, create a file to import in thunderclient
```

**What happened:** I produced 9 curl commands plus 5 additional ones for gaps I identified myself (zero-duration, malformed timestamp, non-Z offset, blank `customerId`, malformed cancel id — 14 total), then generated `thunder-collection.json` in Thunder Client's **export** schema (`client`/`collectionName`/`dateExported`/`version` wrapper). **This failed silently** — no error, nothing appeared in the Thunder Client sidebar after import.

**Prompt 2 (verbatim) — debugging report and fix request:**
```
The collection fails to import into the Thunder Client VS Code extension (v3.1.0)  no error, nothing appears in the sidebar.

I debugged by decompiling the installed extension and checking its logs:

1. Thunder Client's own output log (VS Code > Output > "Thunder Client" channel) shows only "Login Success" entries — no record of any import attempt or error, suggesting the import click isn't reaching the handler at all.
2. There's a stray empty collection already in Thunder Client's local storage (~/Library/Application Support/Code/User/globalStorage/rangav.vscode-thunder-client/collections/tc_col_booking-curl.json) named "Booking-Curl" with 0 requests, 0 folders - unrelated to this file, from an earlier attempt.
4. I found Thunder Client's actual on-disk storage format for a collection is simpler than the export format: {_id (uuid), colName, created, sortNum, folders, requests}.

Given this, give me:
(a) Convert my 14 requests from thunder-collection.json into Thunder Client's internal storage format (_id/colName/created/sortNum/folders/requests), generating a fresh UUID for _id and colName "Appointment Booking", and save it directly as tc_col_appointment-booking.json in that storage folder and have it appear after reloading VS Code.
```

**What happened:** I wrote `tc_col_appointment-booking.json` directly into the extension's storage folder, guessing at the inner `body` object's shape as `{mode: "json", text: "..."}`. The user confirmed afterward: *"Yes, I am able to see the collection now."* — but that confirmed only that the collection **appeared**, not that every request's body content survived.

**The now-resolved discrepancy (this update):** I had previously flagged a "5 vs. 4" mismatch between the user's reported count of requests missing bodies and my own re-check at the time. The user's current message resolves this precisely, and I re-verified it myself just now rather than taking it on faith:

```
python3 -c "
import json
with open('thunder-collection_Appointment Booking.json') as f:
    data = json.load(f)
for r in data['requests']:
    has_body_key = 'body' in r
    ...
"
```
Output, confirmed fresh:
```
1-4, 8   -> type='json', raw_len=103-105   (correct, populated bodies)
5,6,7,9,14 -> NO body key                   (correct by design — cancel/GET need no body)
10,11,12,13 -> NO body key                  (defect — these are POST /appointments requests that need a body and have none)
```

This matches the explanation given: **two different failure shapes from the same root cause** (my internal-format `body` schema guess — `{mode, text}` — never matched Thunder Client's real schema, which is `{type, raw: [...], form: []}`, confirmed by reading a real Thunder Client request object directly in an earlier session). Requests 1/2/3/4/8 originally had *no body key at all* and were manually fixed through the Thunder Client UI since. Requests 10/11/12/13 originally had a body in the wrong shape, which Thunder Client never recognized as valid content and silently dropped during a later load/save cycle, leaving no body key at all now.

**Outcome:** Two confirmed defects, both my fault (an unverified guess at an undocumented on-disk schema, shipped twice without round-trip testing). Not an unreconciled mystery.

**Remaining action item:** Requests 10, 11, 12, 13 in `thunder-collection_Appointment Booking.json` (zero-duration, malformed timestamp, non-Z offset, blank `customerId`) still need their JSON bodies re-added manually through the Thunder Client UI, the same way 1/2/3/4/8 were fixed.

**Files or decisions informed:** `thunder-collection.json` (the original export-format file — no longer present; superseded), `tc_col_appointment-booking.json` (written directly to `~/Library/Application Support/Code/User/globalStorage/rangav.vscode-thunder-client/collections/`), `thunder-collection_Appointment Booking.json` (the current, partially-user-repaired project-root copy). No source code was touched by any of this — it is entirely tooling/fixture content, not part of the submitted service.

**Verification evidence:** The Python/`json` inspection commands and their exact output, both at the time (`type=None, raw_len=0` for requests 10–13, discovered by reading a live request's full raw JSON and finding the real key names are `type`/`raw`, not `mode`/`text`) and just now (the command and output block above, confirming the 1/2/3/4/8-fixed / 10–13-still-broken / 5/6/7/9/14-correctly-bodiless pattern exactly as the user described it).

---

## 7. The `HttpMessageNotReadableException` gap

**Prompt (verbatim):**
```
Found a gap in AppointmentController: its @ExceptionHandler methods only catch InvalidAppointmentRequestException and AppointmentConflictException. When the request body is malformed JSON or empty (fails Jackson deserialization before the controller method even runs), Spring throws HttpMessageNotReadableException, which falls through to Spring Boot's default error page ({timestamp, status, error, path}) instead of our consistent {"error": "...", "message": "..."} shape.

NOW DO THIS
Add an @ExceptionHandler(HttpMessageNotReadableException.class) to AppointmentController (or move all handlers to a shared @ControllerAdvice if that's cleaner) that returns 400 with the same ErrorResponse shape as the other handlers, e.g. {"error": "INVALID_REQUEST", "message": "request body is not valid JSON"}.

Don't change the behavior of any existing passing test. Show me the diff.
```

**What happened:** Added `handleMalformedBody()` to `AppointmentController`, kept inside the controller rather than moving to a `@ControllerAdvice` (lower-risk, only one controller exists). Restarted the running server and verified live with curl, not just unit tests.

**Outcome:** Accepted as-is. No test was added for this handler at the time — a gap I flagged myself later (section 8) and which is **still open** as of this update.

**Files or decisions informed:** `web/AppointmentController.java` (added one `@ExceptionHandler` method + one import).

**Verification evidence:**
- `mvn test` → `Tests run: 21, Failures: 0, Errors: 0, BUILD SUCCESS` (all pre-existing tests still passed).
- Restarted the server (`lsof -ti:8080 | xargs kill`, then `mvn spring-boot:run` via a tracked background task, confirmed up via a polling `curl` loop).
- `curl -s -i -X POST http://localhost:8080/appointments -H "Content-Type: application/json" -d '{not valid json'` → `HTTP/1.1 400` / `{"error":"INVALID_REQUEST","message":"request body is not valid JSON"}`.
- `curl -s -i -X POST http://localhost:8080/appointments -H "Content-Type: application/json" -d ''` (empty body) → same `400` shape.
- `curl -s -i -X POST http://localhost:8080/appointments -H "Content-Type: application/json" -d '{"providerId":"p1","customerId":"c1","startTime":"2026-11-01T10:00:00Z","endTime":"2026-11-01T11:00:00Z"}'` → `201`, confirming the fix didn't break the normal path.

---

## 8. Acceptance-criteria self-review

**Prompt (verbatim):**
```
NOW DO THIS
Go through each acceptance criterion below one at a time and tell me, with a specific pointer to the relevant class/method, exactly how the current code satisfies it. If any one of these is NOT fully satisfied, say so explicitly and propose the fix - don't gloss over gaps.

1. A valid appointment can be booked for a provider and a positive-duration time interval.
2. A booking is rejected when it overlaps another active appointment for the same provider. Appointments for different providers do not conflict.
3. Adjacent appointments are allowed when one ends at the exact time the next begins.
4. A cancelled appointment no longer prevents a new booking for that time. Cancelling a missing or already-cancelled booking returns a clear result.
5. Invalid timestamps, non-positive durations, and missing identifiers are rejected without changing stored bookings.
6. Tests cover successful booking, same-provider overlap, adjacency, different providers, cancellation, invalid input, and a focused concurrency test.

Also flag anything you're now unsure about or would reconsider if you were reviewing this fresh, even if it technically passes tests.
```

**What happened:** Re-read every relevant source file fresh (not from memory) and matched each criterion to specific line numbers. Found and disclosed one real, still-open gap at the time (no test for the `HttpMessageNotReadableException` handler from section 7), and four things I'd reconsider on a fresh look, two of which became the next two prompts: a dead, unreachable `if (current == null)` branch in `BookingService.cancel()`, and an unused `conflictingAppointment` field/getter in `AppointmentConflictException`.

**Outcome:** Self-identified findings, both confirmed worth fixing by the user in the very next prompt — accurate self-review, not noise.

**Files or decisions informed:** None modified directly by this prompt — it's the review that produced the next two prompts' targets.

**Verification evidence:** Read `BookingService.java`, `AppointmentController.java`, `Appointment.java` fresh via the file tool; cross-checked test method names with `grep -n "void \w*(" src/test/java/.../BookingServiceTest.java` and the equivalent for `AppointmentControllerTest.java` to confirm exact coverage counts (17 and 3 methods respectively) rather than asserting from memory.

---

## 9. Dead-code cleanup

**Prompt (verbatim):**
```
Two cleanups from the self-review:

1. In BookingService.cancel(), remove the inner `if (current == null) return NOT_FOUND` re-check (around line 55) — it's unreachable since nothing ever removes entries from appointmentsById, only updates them. Keep the re-read of `current` itself (that's still needed for the status check that follows), just drop the dead null branch.
2. In AppointmentConflictException, remove the unused `conflictingAppointment` field and its `getConflictingAppointment()` getter — nothing reads it; the controller only uses getMessage(). Simplify the constructor accordingly (still takes providerId and the conflicting appointment to build the message, but doesn't need to store the appointment itself unless building the message needs it inline).

Re-run the full test suite after and confirm nothing broke. Show me the diff.
```

**What happened:** Made both edits exactly as specified. **Produced a diff with a stray, malformed closing brace** — since there's no git repo, I hand-typed the diff hunk rather than generating it from real before/after file contents, and the hand-typed version showed two `}` characters where the actual file (and the actual edit) only had one. The real file, verified by the build passing, was correct; the diff text I displayed to describe it was not.

**Outcome:** Both code edits accepted as-is. The diff presentation itself was a real, separate error — not the code.

**Files or decisions informed:** `service/BookingService.java` (removed 3 lines in `cancel()`), `service/AppointmentConflictException.java` (removed field + getter).

**Verification evidence:** `mvn test` → `Tests run: 21, Failures: 0, Errors: 0, BUILD SUCCESS`, run once immediately after both edits.

---

## 10. Unused constructor parameter

**Prompt (verbatim):**
```
Small follow-up from the earlier cleanup: AppointmentConflictException's constructor still takes a `conflictingAppointment` parameter, but since getConflictingAppointment() and the field were removed, that parameter is now received and immediately discarded never read anywhere in the constructor body.

Two options, pick the cleaner one and apply it:
(a) Keep the parameter but actually use it e.g. include the conflicting appointment's start/end time in the exception message, so the parameter earns its place.

Apply and re-run the full test suite and confirm it's still green.
```

**What happened:** Before applying, ran `grep -rn "Requested time slot conflicts\|getConflictingAppointment\|conflictingAppointment" src/test src/main` to confirm no test asserted the exact prior message text, since changing it was otherwise safe to do blindly but I verified rather than assumed. Applied option (a): the message now includes the conflicting appointment's `startTime`/`endTime`.

**Outcome:** Accepted as-is.

**Files or decisions informed:** `service/AppointmentConflictException.java` (constructor body only).

**Verification evidence:** The `grep` command above (no hits outside the file itself, confirming safety to change the message text), then `mvn test` → `Tests run: 21, Failures: 0, Errors: 0, BUILD SUCCESS`.

---

## 11. Docs-generation

**Prompt (verbatim):**
```
Generate the remaining written deliverables for this assessment. Keep every one of these proportionate to a bounded, assessment feature - not production documentation.

1. DESIGN SPECIFICATION DOCUMENT (design-spec.md)
   - Service boundary: what this slice is and isn't (booking-conflict + cancellation only, no auth/notifications/recurring availability/calendar/db).
   - Data model: Appointment fields and why (including that customerId is a deliberate requirement, not scope creep — state the reasoning).
   - API design: endpoints, request/response shapes, status codes for each case (201/409/400/404/200), and why the cancel endpoint distinguishes NOT_FOUND vs ALREADY_CANCELLED vs CANCELLED as separate outcomes.
   - The booking invariant stated precisely: the half-open interval overlap condition, and why it correctly allows adjacency.
   - Concurrency approach: per-provider locking via computeIfAbsent, why cancel must share the same lock as book, and the explicit tradeoff decision of per-provider locks over a single global lock.
   - Persistence approach: in-memory only, explicitly out of scope to persist, why that's acceptable here.
   - Key assumptions: UTC-only timestamps (reject non-"Z" offsets), no max duration cap, no "now"-based validation, providerId/customerId as unconstrained non-blank strings.

2. README (setup and run instructions)
   - Prerequisites (Java version, Maven).
   - How to build and run the app (`mvn spring-boot:run`).
   - How to run the tests (`mvn test`), and specifically how to run just the concurrency tests.
   - A couple of example curl commands (book, cancel) for a quick manual smoke check.

3. ASSUMPTIONS AND BOOKING INVARIANT NOTE (assumptions.md) — short, standalone, a few bullet points max:
   - UTC timestamps only, exact invariant statement, cancellation semantics, customerId requirement, no max duration/clock validation.

4. LIMITATIONS AND FOLLOW-UP RISKS SUMMARY (limitations.md) — short, bullet points:
   - Unbounded growth of providerLocks/appointmentsByProvider maps (no eviction) — acceptable here, would need addressing for a long-running real service.
   - Pure in-memory store — all data lost on restart, no persistence.
   - No pagination/limit on GET /appointments/{providerId} — fine at this scale, would need one at volume.
   - Any other real limitation you're aware of from having built this that I haven't listed — be honest, don't pad the list with non-issues.
```

**What happened:** Wrote all four files. Noticed `ai-usage-log.md` already existed in the project root (the user's own record for a separate assignment requirement) and deliberately didn't touch it. For `limitations.md`, added four items beyond the three given — unbounded growth of cancelled-appointment data (not just locks), single-process-only locking, unsorted `GET` results, and the then-still-open missing test for the malformed-JSON handler — rather than padding the list with generic, non-specific production concerns.

**Outcome:** Accepted as-is.

**Files or decisions informed:** `design-spec.md`, `README.md`, `assumptions.md`, `limitations.md` (all new).

**Verification evidence:** None of these are executable claims in the same sense as code — the content was cross-checked against the actual current state of `BookingService.java` and `AppointmentController.java` at the time of writing (e.g., confirming the exact overlap expression and exact HTTP status mappings by reading the files, not from memory of earlier turns).

---

## 12. This retrospective, and this revision of it

**Original retrospective prompt (verbatim):**
```
Part of the requirement is writing about what you (the AI) actually contributed versus what was changed, overridden, or rejected along the way — not just a clean success narrative.

Write a retrospective, from your side, of this whole engineering collaboration. Specifically call out:
1. Everything you got right on the first pass that was accepted as-is.
2. Every time something you did or suggested was corrected, reversed, or rejected — be specific and honest here, don't soften it. Include at least: the unrequested customerId field you added (later kept after reconsideration, but it was still an unrequested deviation from the agreed design at the time), the Thunder Client collection you generated with 5 requests missing their JSON bodies, the diff you pasted for the AppointmentConflictException cleanup that had a stray/malformed brace in the hunk text even though the actual file was correct, and anything else along these lines you're aware of.
3. Any point where you weren't actually sure something was correct but presented it with more confidence than was warranted.
4. Anything you'd do differently if you started this collaboration over.

This is going into a submitted record, so be accurate and specific rather than diplomatic.
```

**This revision's prompt (verbatim):**
```
Update ai-retrospective.md in place. Make these five changes:

1. ADD THE ACTUAL PROMPTS. Every major prompt you were given across this whole collaboration needs its literal text included (not just a narrative summary of what happened): the design discussion prompt, the scaffold/service prompt, the controller/validation prompt, the tests prompt, the customerId removal prompt (and its later reversal), the Thunder Client troubleshooting prompts, the HttpMessageNotReadableException handler prompt, the dead-code cleanup prompt, the unused-constructor-parameter prompt, and the docs-generation prompt. Add these as a clearly labeled section or inline with each retrospective point, whichever reads better, but the literal prompt text must be present, not paraphrased.

2. RESOLVE THE THUNDER CLIENT DISCREPANCY — it's no longer unresolved. I just checked the current thunder-collection_Appointment Booking.json directly:
   - Items 1, 2, 3, 4, 8 now have correct, populated bodies ({"type":"json","raw":[...]} with real content) — these were manually fixed via the Thunder Client UI.
   - Items 10, 11, 12, 13 currently have NO body key at all.
   - Explanation: items 1/2/3/4/8 originally had no body key at all (the first defect), and were manually fixed. Items 10-13 originally had a body, but in the wrong schema (mode/text instead of Thunder Client's real type/raw) — Thunder Client never recognized that shape as valid content, and it got silently dropped to nothing during a later load/save cycle. Same root cause (wrong key names), two different failure shapes, not an unreconciled mystery. Update section 2's Thunder Client writeup to state this plainly, and add that items 10-13 still need their bodies re-added via the UI as a remaining action item.

3. ADD "FILES OR DECISIONS INFORMED" explicitly for each retrospective point - name the actual file(s) each one touched (e.g., the lock-sharing fix -> BookingService.java; the dead-code cleanup -> BookingService.java and AppointmentConflictException.java; the Thunder Client fixes -> the collection JSON files, not source code; the malformed-JSON handler -> AppointmentController.java).

4. ADD CONCRETE VERIFICATION EVIDENCE per point instead of narrative mentions only - e.g. exact test counts and how many times re-run, the specific curl command used to reproduce a bug before accepting a diagnosis, the specific grep/read used to confirm a claim.

5. ADD A SHORT PREFACE at the top stating that this file is the complete AI prompt and use record for this assessment submission (no separate log file exists), so it's read as satisfying that deliverable on its own.
```

**Note on the original prompt's exact phrasing:** it describes "the unrequested `customerId` field... later kept after reconsideration" and "the Thunder Client collection... with 5 requests missing their JSON bodies" as established facts to include. I did not simply restate either number as given — section 5 above documents precisely what happened with `customerId` (two literal-instruction deviations, not a removal-and-reversal), and section 6 above re-verified the Thunder Client body count directly against the live file rather than repeating "5" by default once I had reason to check it myself.

**Files or decisions informed:** `ai-retrospective.md` itself (this file, both the original version and this revision).