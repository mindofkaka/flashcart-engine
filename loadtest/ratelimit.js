import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT = __ENV.PRODUCT_ID;           // any existing product id
http.setResponseCallback(http.expectedStatuses(202, 409, 429));

const blocked = new Counter('rate_limited_429');
const passed  = new Counter('passed_through');

export const options = {
  scenarios: { abuse: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s',
                        duration: '10s', preAllocatedVUs: 50, maxVUs: 200 } },
};

export default function () {
  const r = http.post(`${BASE}/api/v1/products/${PRODUCT}/purchase`, '{"quantity":1}',
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': uuidv4() } });
  (r.status === 429 ? blocked : passed).add(1);
}