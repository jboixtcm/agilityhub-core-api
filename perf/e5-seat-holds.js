// E5-T06 · S08 T-08-41 / S15 T-15-30 · k6 load and concurrency test of the booking flow (see perf/README.md).
//
//   k6 run -e BASE_URL=http://127.0.0.1:8080 -e CLUB_HOST=perf.example.test -e POOL_FILE=/path/pool.json \
//          -e SCENARIO=peak -e VUS=300 -e ARRIVAL_SECONDS=5 -e SEATS=50 perf/e5-seat-holds.js
//
// Environment (never hard-coded): BASE_URL, CLUB_HOST (Host header of the club), POOL_FILE (JSON array of
// {token, dogId, classIds}: one entry per DISTINCT member, tokens are MEMBER or impersonation tokens), SCENARIO (peak |
// burst | last_seat), VUS (peak/burst: members arriving, default 300, each one a different pool entry; last_seat: the
// pool size), ARRIVAL_SECONDS (peak: the window the arrivals spread over, default 5), SEATS (peak/burst: the free seats
// of the target classes, all of which must end booked; default 50), LAST_SEAT_CLASS (last_seat).
//
// Ruling E28 (docs/DECISIONS_PENDENTS.md): 300 distinct members arrive within the first 5 s of the Sunday-20:00 opening
// on 10 empty classes of 5 seats.
// peak: VUS members arrive evenly over ARRIVAL_SECONDS and each runs the member flow once: GET /me/bookable-classes →
// POST /seat-holds on one of its target classes → POST /bookings with `Idempotency-Key` = the seatHoldId (R-08-08).
// Thresholds: p(95) of POST /seat-holds < 500 ms (T-08-41) and of the whole flow < 800 ms (gate E5); every answer is an
// expected one (list 200; hold 201 or 409 CLASS_FULL/SEAT_TAKEN; confirmation 201), exactly SEATS holds and SEATS
// bookings, VUS − SEATS rejected holds. The harness asserts in Mongo that the seats are booked and nothing is overbooked.
// burst: the same flow with every member starting at the same instant: the same correctness thresholds; its latencies
// are reported, not gated.
// last_seat: each VU holds the class's single free seat at the same instant and the winner confirms: exactly one 201
// hold, VUS−1 × 409 CLASS_FULL/SEAT_TAKEN, exactly one booking.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const HOST = __ENV.CLUB_HOST;
const MODE = __ENV.SCENARIO || 'peak';
const POOL = JSON.parse(open(__ENV.POOL_FILE));
if (!BASE || !HOST || POOL.length === 0) { throw new Error('BASE_URL, CLUB_HOST and a non-empty POOL_FILE are required'); }
const VUS = parseInt(__ENV.VUS || (MODE === 'last_seat' ? String(POOL.length) : '300'), 10);
const ARRIVAL_SECONDS = parseInt(__ENV.ARRIVAL_SECONDS || '5', 10);
const SEATS = parseInt(__ENV.SEATS || (MODE === 'last_seat' ? '1' : '50'), 10);
if (new Set(POOL.map(p => p.dogId)).size !== POOL.length) { throw new Error('POOL_FILE entries must be distinct members'); }
if (POOL.length < VUS) { throw new Error(`POOL_FILE holds ${POOL.length} distinct members, ${VUS} needed: no member arrives twice`); }

const flow = new Trend('flow_duration', true);
const holdsCreated = new Counter('holds_created');
const holdsRejected = new Counter('holds_rejected');
const bookingsCreated = new Counter('bookings_created');
const unexpected = new Counter('unexpected_answers');
// Answer histogram (the harness prints it): one counter per expected answer, plus unexpected_answers.
const ANSWER_METRICS = {
    'list 200': 'answer_list_200',
    'hold 201': 'answer_hold_201',
    'hold 409 CLASS_FULL': 'answer_hold_409_CLASS_FULL',
    'hold 409 SEAT_TAKEN': 'answer_hold_409_SEAT_TAKEN',
    'booking 201': 'answer_booking_201',
};
const ANSWERS = Object.fromEntries(Object.entries(ANSWER_METRICS).map(([label, metric]) => [label, new Counter(metric)]));
const REJECTIONS = ['CLASS_FULL', 'SEAT_TAKEN'];

// Only 409 is a business answer here (CLASS_FULL / SEAT_TAKEN, asserted by code below); any 422 is a failed request.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

const SCENARIOS = {
    // Arrivals start at k × ARRIVAL_SECONDS / VUS, k = 0 … VUS − 1; the window closes 1 ms early because k6 also starts an
    // iteration on the closing instant itself (a 301st arrival in the first round-2 run).
    peak: { executor: 'constant-arrival-rate', rate: VUS, timeUnit: `${ARRIVAL_SECONDS}s`, duration: `${ARRIVAL_SECONDS * 1000 - 1}ms`,
        preAllocatedVUs: VUS, maxVUs: VUS, exec: 'member' },
    burst: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '3m', exec: 'member' },
    last_seat: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m', exec: 'lastSeat' },
};
const DISPLAY = { 'http_req_duration{name:bookable-classes}': ['p(95)<60000'], 'http_req_duration{name:bookings}': ['p(95)<60000'] };
const CORRECTNESS = {
    'http_req_failed': ['rate==0'],
    'unexpected_answers': ['count==0'],
    'holds_created': [`count==${SEATS}`],
    'holds_rejected': [`count==${VUS - SEATS}`],
    'bookings_created': [`count==${SEATS}`],
    'dropped_iterations': ['count==0'],
    // Every member arrives exactly once.
    'iterations': [`count==${VUS}`],
};
const THRESHOLDS = {
    peak: Object.assign({
        'flow_duration': ['p(95)<800'],
        'http_req_duration{name:seat-holds}': ['p(95)<500'],
    }, CORRECTNESS, DISPLAY),
    burst: Object.assign({
        // Display only: the burst's latencies are reported, not gated (ruling E28).
        'flow_duration': ['p(95)<600000'],
        'http_req_duration{name:seat-holds}': ['p(95)<60000'],
    }, CORRECTNESS, DISPLAY),
    last_seat: CORRECTNESS,
};
// Every histogram counter reaches the summary export even when it stays at zero.
Object.values(ANSWER_METRICS).forEach(metric => { THRESHOLDS[MODE][metric] = ['count>=0']; });
export const options = {
    scenarios: { [MODE]: SCENARIOS[MODE] },
    thresholds: THRESHOLDS[MODE],
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function params(token, name, key) {
    const headers = { Host: HOST, Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
    if (key) { headers['Idempotency-Key'] = key; }
    return { headers, tags: { name } };
}
function code(response) {
    try { return response.json('code') || ''; } catch (e) { return ''; }
}
/** Counts the answer; anything outside the expected set is unexpected and printed (status and error code only). */
function answer(step, response, expected) {
    const label = `${step} ${response.status}` + (response.status >= 400 ? ` ${code(response)}` : '');
    const ok = expected.includes(label);
    if (ok) { ANSWERS[label].add(1); } else { unexpected.add(1); console.warn(`UNEXPECTED ${label}`); }
    check(response, { [`${step}: ${expected.join(' | ')}`]: () => ok });
    return ok;
}
function hold(me, classId) {
    const response = http.post(`${BASE}/api/v1/seat-holds`, JSON.stringify({ classSessionId: classId, dogId: me.dogId }), params(me.token, 'seat-holds'));
    if (!answer('hold', response, ['hold 201', ...REJECTIONS.map(r => `hold 409 ${r}`)])) { return; }
    if (response.status !== 201) { holdsRejected.add(1); return; }
    holdsCreated.add(1);
    const holdId = response.json('id');
    // R-08-08: the confirmation's Idempotency-Key is the seatHoldId.
    const booking = http.post(`${BASE}/api/v1/bookings`, JSON.stringify({ seatHoldId: holdId }), params(me.token, 'bookings', holdId));
    if (answer('booking', booking, ['booking 201'])) { bookingsCreated.add(1); }
}

export function member() {
    const index = exec.scenario.iterationInTest;
    const me = POOL[index];
    const started = Date.now();
    const list = http.get(`${BASE}/api/v1/me/bookable-classes?dogId=${me.dogId}`, params(me.token, 'bookable-classes'));
    answer('list', list, ['list 200']);
    const open = list.status === 200 ? list.json('classes').filter(c => me.classIds.includes(c.id) && c.state === 'BOOKABLE').map(c => c.id) : [];
    const targets = open.length > 0 ? open : me.classIds;
    hold(me, targets[index % targets.length]);
    flow.add(Date.now() - started);
}

export function lastSeat() {
    hold(POOL[exec.vu.idInTest - 1], __ENV.LAST_SEAT_CLASS);
}
