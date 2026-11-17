import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE  = __ENV.BASE_URL || 'http://localhost:8080';
const STOCK = parseInt(__ENV.STOCK || '5000');
const PEAK  = parseInt(__ENV.PEAK_RPS || '1000');

// 409 (sold out) is an expected outcome, not a failure
http.setResponseCallback(http.expectedStatuses(202, 409));

const accepted   = new Counter('purchase_accepted');    // 202, first time
const soldOut    = new Counter('purchase_sold_out');    // 409 fast-fail
const replayed   = new Counter('purchase_replayed');    // idempotent duplicate served from cache
const unexpected = new Counter('purchase_unexpected');  // 429, 5xx, 400...

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'ramping-arrival-rate',   // fixed request rate, independent of response time
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2000,
      stages: [
        { target: Math.round(PEAK * 0.3), duration: '30s' },  // warm-up
        { target: PEAK,                   duration: '30s' },  // spike
        { target: PEAK,                   duration: '60s' },  // sustained (mostly sold out)
        { target: 0,                      duration: '10s' },
      ],
    },
  },
  thresholds: {
    http_req_duration:   ['p(95)<200', 'p(99)<500'],
    purchase_unexpected: ['count==0'],
    purchase_accepted:   [`count==${STOCK}`],   // exactly STOCK orders: no oversell, no undersell
    dropped_iterations:  ['count<1'],           // k6 itself kept up
  },
  teardownTimeout: '150s',
};

const params = (key) => ({
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
});

function buy(productId, key) {
  return http.post(`${BASE}/api/v1/products/${productId}/purchase`,
                   JSON.stringify({ quantity: 1 }), params(key));
}

export function setup() {
  const res = http.post(`${BASE}/api/v1/products`,
    JSON.stringify({ name: 'k6 Flash Sneakers', description: 'load test', price: 100000, stock: STOCK }),
    { headers: { 'Content-Type': 'application/json' }, responseCallback: http.expectedStatuses(201) });
  if (res.status !== 201) throw new Error(`setup failed: ${res.status} ${res.body}`);
  const productId = res.json('id');
  console.log(`Product ${productId} created with stock ${STOCK}`);
  return { productId };
}

export default function (data) {
  const key = uuidv4();
  const res = buy(data.productId, key);

  if (res.status === 202) {
    accepted.add(1);
    if (Math.random() < 0.1) {            // simulate a client retry / double-click
      const dup = buy(data.productId, key);
      replayed.add(1);
      check(dup, {
        'duplicate replayed, same orderId': (r) =>
          r.status === 202 &&
          r.headers['Idempotency-Replayed'] === 'true' &&
          r.json('orderId') === res.json('orderId'),
      });
    }
  } else if (res.status === 409) {
    soldOut.add(1);
  } else {
    unexpected.add(1);
  }
}

// After the load stops, wait for the stream worker to drain and report the Postgres stock
export function teardown(data) {
  const t0 = Date.now();
  let stock = -1;
  for (let i = 0; i < 120; i++) {
    const r = http.get(`${BASE}/api/v1/products/${data.productId}`,
                       { responseCallback: http.expectedStatuses(200) });
    stock = r.json('stock');
    if (stock === 0) break;
    sleep(1);
  }
  console.log(`Product ${data.productId}: Postgres stock=${stock}, ` +
              `drained ${(Date.now() - t0) / 1000}s after load stopped`);
}