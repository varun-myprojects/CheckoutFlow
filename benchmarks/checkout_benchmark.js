import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const checkoutLatency = new Trend('checkout_duration');
const successfulCheckouts = new Counter('checkouts_successful');
const stockExhaustedCheckouts = new Counter('checkouts_stock_exhausted');
const unexpectedFailures = new Counter('checkouts_unexpected_failure');

export const options = {
  scenarios: {
    concurrent_checkouts: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 }, // Warm-up
        { duration: '30s', target: 50 }, // 50 concurrent checkout users
        { duration: '10s', target: 0 },  // Ramp-down
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    checkout_duration: ['p(95)<400', 'p(99)<800'],
    checkouts_unexpected_failure: ['count<1'], // Zero 5xx or unhandled server errors allowed
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';

export default function () {
  // Use virtual user ID (1-50) to ensure separate user carts
  const userId = (__VU % 50) + 1;
  const productId = Math.floor(Math.random() * 5) + 1; // High contention on products 1-5
  const idempotencyKey = `k6-chk-${__VU}-${__ITER}-${Date.now()}`;

  // 1. Add item to cart
  const addItemPayload = JSON.stringify({
    productId: productId,
    quantity: 1,
  });

  const addRes = http.post(`${BASE_URL}/carts/${userId}/items`, addItemPayload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'AddToCart' },
  });

  if (addRes.status !== 200 && addRes.status !== 201) {
    // Cart might already contain item or stock low; proceed to checkout if items exist
  }

  sleep(0.02);

  // 2. Perform concurrent Checkout
  const checkoutRes = http.post(`${BASE_URL}/checkout/${userId}`, null, {
    headers: {
      'Idempotency-Key': idempotencyKey,
      'Content-Type': 'application/json',
    },
    tags: { name: 'Checkout' },
  });

  checkoutLatency.add(checkoutRes.timings.duration);

  // 3. Classify outcome
  if (checkoutRes.status === 201) {
    successfulCheckouts.add(1);
    check(checkoutRes, {
      'checkout status is 201': (r) => r.status === 201,
      'order confirmed or pending': (r) => {
        try {
          const json = r.json();
          return json.success === true && json.data.orderId !== undefined;
        } catch {
          return false;
        }
      },
    });
  } else if (checkoutRes.status === 400 || checkoutRes.status === 409) {
    // Clean rejection due to depleted stock or empty cart
    stockExhaustedCheckouts.add(1);
  } else {
    // Unexpected 5xx or connection error
    unexpectedFailures.add(1);
    check(checkoutRes, {
      'no unexpected 500 error': (r) => r.status < 500,
    });
  }

  sleep(0.1);
}
