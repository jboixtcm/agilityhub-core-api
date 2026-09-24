# Load and concurrency tests (k6)

`perf/e5-seat-holds.js` is the E5 gate's load test for the booking flow: S08 T-08-41 (the opening peak, no
overbooking) and S15 T-15-30 (the same peak while P1 `week-opening` fans N-33 out). The load model is the organizer's
ruling **E28** (`docs/DECISIONS_PENDENTS.md`): **300 distinct fictional members** arrive within the first **5 s** of the
Sunday-20:00 opening, on **10 empty classes of 5 seats**; `p(95) < 500 ms` on the seat hold and `< 800 ms` on the whole
flow, zero overbooking. The all-at-once burst stays a correctness stress run whose latencies are reported, not gated.
The script uses only environment variables; nothing is hard-coded:

| Variable | Meaning |
|---|---|
| `BASE_URL` | API root, e.g. `http://127.0.0.1:8080` |
| `CLUB_HOST` | `Host` header of the club (the tenant comes from it and from the token) |
| `POOL_FILE` | JSON array of `{token, dogId, classIds}`: one **distinct** member per entry (the script refuses repeated dogs). Tokens are MEMBER tokens or impersonation tokens (same code path). Keep the file private: it holds bearer tokens |
| `SCENARIO` | `peak` (default), `burst` or `last_seat` |
| `VUS` | `peak`/`burst`: members arriving (default 300; the pool must hold at least as many, nobody arrives twice); `last_seat`: the pool size (default all entries) |
| `ARRIVAL_SECONDS` | `peak`: the window the arrivals spread over, evenly (default 5) |
| `SEATS` | `peak`/`burst`: the free seats of the target classes, all of which must end booked (default 50) |
| `LAST_SEAT_CLASS` | `last_seat`: the class that has exactly one free seat |

Every scenario runs the same member step: `POST /seat-holds` → on 201 `POST /bookings` with `Idempotency-Key` = the
`seatHoldId` (S08 R-08-08). `peak` and `burst` list first (`GET /me/bookable-classes`) and hold one of the member's
target classes that the list shows `BOOKABLE`. **Every answer is asserted by status and error code**: the list 200, the
hold 201 or `409 CLASS_FULL` / `409 SEAT_TAKEN`, the confirmation 201. Anything else (a 422, another 409 code, a 5xx)
counts in `unexpected_answers`, is printed as `UNEXPECTED <step> <status> <code>` and fails the run; only 409 is an
expected HTTP failure status, so `http_req_failed` must stay at 0. The answers are also counted per label
(`answer_list_200`, `answer_hold_201`, `answer_hold_409_CLASS_FULL`, `answer_hold_409_SEAT_TAKEN`,
`answer_booking_201`): the harness prints that histogram.

- **`peak`** (gated): `VUS` members arrive evenly over `ARRIVAL_SECONDS` (`constant-arrival-rate`, exactly `VUS`
  iterations). Thresholds: `p(95) < 500 ms` on `POST /seat-holds` (`http_req_duration{name:seat-holds}`, T-08-41) and
  `p(95) < 800 ms` on the flow (`flow_duration`, gate E5); exactly `SEATS` holds and `SEATS` bookings, `VUS − SEATS`
  rejected holds, no unexpected answer, no dropped iteration.
- **`burst`**: the same flow with every member starting at the same instant. The same correctness thresholds; its
  latencies are reported, not gated (E28), because 300 flows started in the same millisecond measure how long a
  single-node laptop stack takes to drain a queue, not a member's wait at a real opening. To be measured again on
  staging (E0-T13).
- **`last_seat`** (gated): each VU holds the single free seat of `LAST_SEAT_CLASS` at the same moment and the winner
  confirms: `holds_created == 1`, `holds_rejected == VUS − 1` (409 `CLASS_FULL`/`SEAT_TAKEN` only),
  `bookings_created == 1`.

k6 does not see the database. The harness asserts in Mongo after each run that the target classes are full and that
no class holds more `ACTIVE` + `PAYMENT_PENDING` bookings than its capacity.

## One command: `bin/e5-perf`

```sh
bin/e5-perf              # builds the working tree image; k6 from PATH, else the grafana/k6 image
bin/e5-perf --image      # the published consumer image (it packages seeds/club-perf.yaml and demo-perf.yaml)
bin/e5-perf --vus 300 --arrival-seconds 5 --k6 docker
```

The harness starts a disposable Compose stack (the `bin/e5-smoke` machinery) and seeds only the fictional
**load-test club** `perf` (`seeds/club-perf.yaml` + `seeds/demo-perf.yaml`, twice: the second run must report
`0 changes`) with `--week-start` 8+ days ahead. That club has 330 members with one dog each, one level with
`levels.enabled = false` (every dog may book every class), the N-33 fan-out on, and eleven empty 5-seat classes in each
of the two weeks after the anchor week. The seed goes through the census and planning services (no booking is
seeded). Then:

1. Switches P2 off and moves the test clock to 30 minutes before the Sunday-20:00 opening that closes the anchor week:
   week +2 becomes W1 (the week that opens, 1 booking per dog) and week +1 is W0 (2 per dog).
2. Mints one impersonation token per member (`POST /members/{id}/impersonation-token`, 60 min): 329 distinct members
   (the admin cannot be impersonated). Demo members are passwordless, and the booking code path is the member's.
3. Sets the clock 20 s before 20:00, waits for the scheduler's P1 run and starts `peak`: 300 distinct members on the
   10 classes of W1, while N-33 fans out. It asserts 50/50 seats booked by 50 distinct members, zero overbooking, and
   that the last N-33 row lands less than 10 s after `WeekOpened` (T-15-30).
4. Fills the eleventh W0 class to capacity − 1 with 4 members and runs `last_seat` with 50 others; asserts 5/5.
5. Runs `burst`: the same 300 members all at once on the 10 other W0 classes; asserts 50/50 and zero overbooking.

Each run prints the k6 end-of-test summary (metric names and numbers only) and one `RESULT` line: distinct members,
seats filled per class, the answer histogram, the p95 figures and the overbooking check. The exit code is non-zero
when a threshold or an assertion fails. Tokens stay in a private temporary directory that is removed at the end,
together with the stack; `SEED_PASSWORD` is generated per run and never printed.

Before E5-T06 a burst of 300 simultaneous requests stalled the whole API: on JDK 21 virtual threads, Caffeine loaders
doing Mongo I/O inside a map lock pinned every carrier. The per-request caches now go through
`shared.application.CacheLoads`, which loads outside the lock and drops a loaded value when an invalidation overlapped
the load (E5-T06 round 2). The seat hold also runs its ordered checks once without the class lock, so members who
cannot get a seat do not queue behind that class's lane (a `CLASS_FULL{heldOnly}` is left to the locked path).

Against another stack (for example staging, whose clock cannot move), prepare a pool file of distinct members yourself
and run `k6 run -e BASE_URL=… -e CLUB_HOST=… -e POOL_FILE=… -e SCENARIO=peak -e VUS=300 -e SEATS=50 perf/e5-seat-holds.js`
at the opening.
