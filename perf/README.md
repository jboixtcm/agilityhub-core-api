# Load and concurrency tests (k6)

`perf/e5-seat-holds.js` is the E5 gate's load test for the booking flow: S08 T-08-41 (the opening peak, no
overbooking) and S15 T-15-30 (the same peak while P1 `week-opening` fans N-33 out). It uses only environment
variables; nothing is hard-coded:

| Variable | Meaning |
|---|---|
| `BASE_URL` | API root, e.g. `http://127.0.0.1:8080` |
| `CLUB_HOST` | `Host` header of the club (the tenant comes from it and from the token) |
| `POOL_FILE` | JSON array of `{token, dogId, classIds}`: one member per entry. Tokens are MEMBER tokens or impersonation tokens (same code path). Keep the file private: it holds bearer tokens |
| `SCENARIO` | `peak` (default), `burst` or `last_seat` |
| `VUS` | `peak`/`burst`: members arriving (default 300); `last_seat`: the pool size (default all entries) |
| `ARRIVAL_SECONDS` | `peak`: the window the arrivals spread over, evenly (default 5) |
| `LAST_SEAT_CLASS` | `last_seat`: the class that has exactly one free seat |

- **`peak`**: `VUS` members arrive at the Sunday-20:00 opening, evenly over `ARRIVAL_SECONDS` (`constant-arrival-rate`).
  Each runs the member flow once: `GET /me/bookable-classes` → `POST /seat-holds` → `POST /bookings` on one of its
  target classes. The thresholds hold both targets. The gate asks for `p(95) < 800 ms` on the whole flow
  (`flow_duration`), and T-08-41 for `p(95) < 500 ms` on `POST /seat-holds` (`http_req_duration{name:seat-holds}`).
  `http_req_failed` must stay at 0; 409/422 count as expected business answers (`CLASS_FULL`,
  `BOOKING_LIMIT_REACHED`, `NOT_YET_OPEN`, `SWAP_NOT_ALLOWED`…).
- **`burst`**: the same flow with every member starting at the same instant, a stress test. Only correctness is
  gated: no failed request, no unexpected answer, no overbooking. Its latencies are reported but not gated, because
  300 flows started in the same millisecond measure how long a single-node stack takes to drain a queue, not a
  member's wait at a real opening.
- **`last_seat`**: each VU holds the single free seat of `LAST_SEAT_CLASS` at the same moment, and the winner
  confirms. The thresholds are `holds_created == 1`, `holds_rejected == VUS − 1` (409 `CLASS_FULL`/`SEAT_TAKEN` only)
  and `bookings_created == 1`.

k6 does not see the database. The harness asserts overbooking in Mongo after each run: no class may hold more
`ACTIVE` + `PAYMENT_PENDING` bookings than its capacity.

## One command: `bin/e5-perf`

```sh
bin/e5-perf              # builds the working tree image; k6 from PATH, else the grafana/k6 image
bin/e5-perf --image      # the published consumer image
bin/e5-perf --vus 300 --k6 docker
```

The harness starts a disposable Compose stack, seeded like `bin/e5-smoke` with `--week-start` 8+ days ahead. It then
takes these steps:

1. Moves the test clock to 30 minutes before the Sunday-20:00 opening of the validated W+2 and switches P2 off.
2. Picks the ten 5-seat classes of W+2 that the most members can book, plus the widest-level 5-seat class for the
   last-seat run.
3. Mints one impersonation token per member (`POST /members/{id}/impersonation-token`, 60 min). Demo members are
   passwordless, and the booking code path is the member's.
4. Sets the clock 20 s before 20:00 and waits for the scheduler's P1 run, then starts `peak`, so the N-33 fan-out and
   the peak overlap. It asserts zero overbooking, and that the last N-33 row lands less than 10 s after `WeekOpened`.
5. Fills the last-seat class to capacity − 1 and runs `last_seat` with 50 other members. It asserts exactly
   `capacity` live bookings.
6. Runs `burst` on ten other 5-seat classes, with members whose dogs hold no W+2 booking yet, and asserts zero
   overbooking.

Before E5-T06 a burst of 300 simultaneous requests stalled the whole API: on JDK 21 virtual threads, Caffeine loaders
doing Mongo I/O inside a map lock pinned every carrier. Loader lookups now go through
`shared.application.CacheLoads`, which loads outside the lock. The seat hold also runs its ordered checks once
without the class lock, so the members who cannot get a seat never queue behind that class's lane.

It prints the k6 end-of-test summaries (metric names and numbers only) and one `RESULT` line per scenario. The exit
code is non-zero when a threshold or an assertion fails. Tokens stay in a private temporary directory that is removed
at the end, together with the stack.

Against another stack (for example staging, whose clock cannot move), prepare a pool file yourself and run
`k6 run -e BASE_URL=… -e CLUB_HOST=… -e POOL_FILE=… -e SCENARIO=peak -e VUS=300 perf/e5-seat-holds.js` at the opening.
