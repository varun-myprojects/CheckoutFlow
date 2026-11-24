import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// Custom metrics to track individual endpoint latencies
const catalogLatency = new Trend('catalog_read_duration');
const cartLatency = new Trend('cart_read_duration');
const savedItemsLatency = new Trend('saved_items_read_duration');
const errorRate = new Rate('error_rate');

export const options = {
  stages: [
    { duration: '10s', target: 20 },  // Ramp up to 20 VUs
    { duration: '30s', target: 50 },  // Sustained load at 50 VUs
    { duration: '20s', target: 100 }, // Peak load at 100 VUs
    { duration: '10s', target: 0 },   // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<200', 'p(99)<500'], // 95% of requests under 200ms
    error_rate: ['rate<0.01'],                     // Less than 1% errors
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';

export default function () {
  // Distribute load across 10 sample products and 10 sample users
  const productId = Math.floor(Math.random() * 10) + 1;
  const userId = Math.floor(Math.random() * 10) + 1;

  // 1. Benchmark: Product Catalog Read (Cache-Aside: product:{id})
  const catalogRes = http.get(`${BASE_URL}/products/${productId}`, {
    tags: { name: 'GetProductById' },
  });
  catalogLatency.add(catalogRes.timings.duration);
  const catalogSuccess = check(catalogRes, {
    'catalog status is 200': (r) => r.status === 200,
    'catalog response has body': (r) => r.body.length > 0,
  });
  errorRate.add(!catalogSuccess);

  sleep(0.05);

  // 2. Benchmark: User Cart Read (Cache-Aside: cart:{userId} + 2N+1 eliminated)
  const cartRes = http.get(`${BASE_URL}/carts/${userId}`, {
    tags: { name: 'GetCart' },
  });
  cartLatency.add(cartRes.timings.duration);
  const cartSuccess = check(cartRes, {
    'cart status is 200': (r) => r.status === 200,
    'cart response has body': (r) => r.body.length > 0,
  });
  errorRate.add(!cartSuccess);

  sleep(0.05);

  // 3. Benchmark: Save-for-Later Read (Cache-Aside: saved:{userId} + composite index)
  const savedRes = http.get(`${BASE_URL}/saved-items/${userId}`, {
    tags: { name: 'GetSavedItems' },
  });
  savedItemsLatency.add(savedRes.timings.duration);
  const savedSuccess = check(savedRes, {
    'saved-items status is 200': (r) => r.status === 200,
    'saved-items response has body': (r) => r.body.length > 0,
  });
  errorRate.add(!savedSuccess);

  sleep(0.1);
}
