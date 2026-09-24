// E5-T06 · S08 T-08-41 / S15 T-15-30 · k6 load and concurrency test of the booking flow (see perf/README.md).
//
//   k6 run -e BASE_URL=http://127.0.0.1:8080 -e CLUB_HOST=app.example.test -e POOL_FILE=/path/pool.json \
//          -e SCENARIO=peak -e VUS=300 -e ARRIVAL_SECONDS=5 perf/e5-seat-holds.js
//
// Environment (never hard-coded): BASE_URL, CLUB_HOST (Host header of the club), POOL_FILE (JSON array of
// {token, dogId, classIds}; one entry per member, tokens are MEMBER or impersonation tokens), SCENARIO (peak | burst |
// last_seat), VUS (peak/burst: members arriving, default 300; last_seat: the pool size), ARRIVAL_SECONDS (peak: the
// window the arrivals spread over, default 5), LAST_SEAT_CLASS (last_seat).
//
// peak: VUS members arrive at the Sunday-20:00 opening, evenly over ARRIVAL_SECONDS, and each runs the member flow
// GET /me/bookable-classes → POST /seat-holds → POST /bookings once, on one of its target classes. Thresholds:
// p(95) of the whole flow < 800 ms (gate E5), p(95) of POST /seat-holds < 500 ms (T-08-41), and nothing but the
// expected business answers (409/422).
// burst: the same flow with every member starting at the same instant, a stress test with correctness thresholds only
// (its latencies are reported, not gated).
// last_seat: each VU holds the class's single free seat at the same instant and the winner confirms: exactly one 201
// hold, VUS−1 × 409 CLASS_FULL/SEAT_TAKEN, exactly one booking. The harness asserts overbooking in Mongo.
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

const flow = new Trend('flow_duration', true);
const holdsCreated = new Counter('holds_created');
const holdsRejected = new Counter('holds_rejected');
const bookingsCreated = new Counter('bookings_created');
const bookingsRejected = new Counter('bookings_rejected');
const unexpected = new Counter('unexpected_answers');

// 409 (CLASS_FULL, SEAT_TAKEN, BOOKING_LIMIT_REACHED…) and 422 (NOT_YET_OPEN, SWAP_NOT_ALLOWED…) are business answers.
http.setResponseCallback(http.expectedStatuses(200, 201, 409, 422));

const SCENARIOS = {
    peak: { executor: 'constant-arrival-rate', rate: VUS, timeUnit: `${ARRIVAL_SECONDS}s`, duration: `${ARRIVAL_SECONDS}s`,
        preAllocatedVUs: VUS, maxVUs: VUS, exec: 'member' },
    burst: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '3m', exec: 'member' },
    last_seat: { executor: 'per-vu-iterations', vus: Math.min(VUS, POOL.length), iterations: 1, maxDuration: '2m', exec: 'lastSeat' },
};
const DISPLAY = { 'http_req_duration{name:bookable-classes}': ['p(95)<60000'], 'http_req_duration{name:bookings}': ['p(95)<60000'] };
const THRESHOLDS = {
    peak: Object.assign({
        'flow_duration': ['p(95)<800'],
        'http_req_duration{name:seat-holds}': ['p(95)<500'],
        'http_req_failed': ['rate==0'],
        'unexpected_answers': ['count==0'],
    }, DISPLAY),
    burst: Object.assign({
        'http_req_failed': ['rate==0'],
        'unexpected_answers': ['count==0'],
        // Display only: the burst's latencies are reported, not gated.
        'flow_duration': ['p(95)<600000'],
        'http_req_duration{name:seat-holds}': ['p(95)<60000'],
    }, DISPLAY),
    last_seat: {
        'holds_created': ['count==1'],
        'holds_rejected': [`count==${Math.min(VUS, POOL.length) - 1}`],
        'bookings_created': ['count==1'],
        'http_req_failed': ['rate==0'],
        'unexpected_answers': ['count==0'],
    },
};
export const options = {
    scenarios: { [MODE]: SCENARIOS[MODE] },
    thresholds: THRESHOLDS[MODE],
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
}
function params(token, name, key) {
    const headers = { Host: HOST, Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
    if (key) { headers['Idempotency-Key'] = key; }
    return { headers, tags: { name } };
}
function code(response) {
    try { return response.json('code'); } catch (e) { return ''; }
}
function confirm(member, holdId) {
    const booking = http.post(`${BASE}/api/v1/bookings`, JSON.stringify({ seatHoldId: holdId }), params(member.token, 'bookings', uuid()));
    if (booking.status === 201) { bookingsCreated.add(1); }
    else if (booking.status === 409 || booking.status === 422) { bookingsRejected.add(1); }
    else { unexpected.add(1); console.warn(`unexpected confirmation answer ${booking.status} ${code(booking)}`); }
    check(booking, { 'confirmation answers 201, 409 or 422': r => [201, 409, 422].includes(r.status) });
}

export function member() {
    const index = exec.scenario.iterationInTest;
    const me = POOL[index % POOL.length];
    const started = Date.now();
    const list = http.get(`${BASE}/api/v1/me/bookable-classes?dogId=${me.dogId}`, params(me.token, 'bookable-classes'));
    check(list, { 'bookable classes 200': r => r.status === 200 });
    const open = list.status === 200 ? list.json('classes').filter(c => me.classIds.includes(c.id) && c.state === 'BOOKABLE').map(c => c.id) : [];
    const targets = open.length > 0 ? open : me.classIds;
    const target = targets[index % targets.length];
    const hold = http.post(`${BASE}/api/v1/seat-holds`, JSON.stringify({ classSessionId: target, dogId: me.dogId }), params(me.token, 'seat-holds'));
    if (hold.status === 201) {
        holdsCreated.add(1);
        confirm(me, hold.json('id'));
    } else if (hold.status === 409 || hold.status === 422) {
        holdsRejected.add(1);
    } else {
        unexpected.add(1); console.warn(`unexpected hold answer ${hold.status} ${code(hold)}`);
    }
    flow.add(Date.now() - started);
}

export function lastSeat() {
    const me = POOL[exec.vu.idInTest - 1];
    const hold = http.post(`${BASE}/api/v1/seat-holds`, JSON.stringify({ classSessionId: __ENV.LAST_SEAT_CLASS, dogId: me.dogId }),
        params(me.token, 'seat-holds'));
    if (hold.status === 201) {
        holdsCreated.add(1);
        confirm(me, hold.json('id'));
    } else if (hold.status === 409 && ['CLASS_FULL', 'SEAT_TAKEN'].includes(code(hold))) {
        holdsRejected.add(1);
    } else {
        unexpected.add(1); console.warn(`unexpected hold answer ${hold.status} ${code(hold)}`);
    }
    check(hold, { 'last seat: 201 or 409 CLASS_FULL/SEAT_TAKEN': r => r.status === 201 || ['CLASS_FULL', 'SEAT_TAKEN'].includes(code(r)) });
}
