import http from 'k6/http';
import { check, fail, group, sleep } from 'k6';

function readBoolean(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  if (raw === 'true') {
    return true;
  }
  if (raw === 'false') {
    return false;
  }
  throw new Error(`${name} must be true or false`);
}

function readInteger(name, fallback, minimum, maximum) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  if (!/^\d+$/.test(raw)) {
    throw new Error(`${name} must be an integer`);
  }
  const value = Number.parseInt(raw, 10);
  if (value < minimum || value > maximum) {
    throw new Error(`${name} must be between ${minimum} and ${maximum}`);
  }
  return value;
}

function readDecimal(name, fallback, minimum, maximum) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  if (!/^\d+(\.\d+)?$/.test(raw)) {
    throw new Error(`${name} must be a decimal number`);
  }
  const value = Number.parseFloat(raw);
  if (value < minimum || value > maximum) {
    throw new Error(`${name} must be between ${minimum} and ${maximum}`);
  }
  return value;
}

function readDuration(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  if (!/^\d+(ms|s|m)$/.test(raw)) {
    throw new Error(`${name} must be a k6 duration using ms, s, or m`);
  }
  if (Number.parseInt(raw, 10) === 0) {
    throw new Error(`${name} must be greater than zero`);
  }
  return raw;
}

function readBaseUrl(name, fallback) {
  const raw = (__ENV[name] || fallback).replace(/\/+$/, '');
  if (!/^https?:\/\/[^/@\s]+(?::\d+)?$/.test(raw)) {
    throw new Error(`${name} must be an HTTP(S) origin without credentials or a path`);
  }
  return raw;
}

function readRunId() {
  const supplied = __ENV.RUN_ID;
  if (supplied !== undefined && supplied !== '') {
    const sanitized = supplied.replace(/[^A-Za-z0-9-]/g, '').slice(0, 20);
    if (sanitized === '') {
      throw new Error('RUN_ID must contain at least one letter, digit, or hyphen');
    }
    return sanitized;
  }
  return `${Date.now().toString(36)}-${randomHex(6)}`;
}

function randomHex(length) {
  let result = '';
  for (let index = 0; index < length; index += 1) {
    result += Math.floor(Math.random() * 16).toString(16);
  }
  return result;
}

function uuidV4() {
  const variant = (8 + Math.floor(Math.random() * 4)).toString(16);
  return `${randomHex(8)}-${randomHex(4)}-4${randomHex(3)}-${variant}${randomHex(3)}-${randomHex(12)}`;
}

const authorizationBaseUrl = readBaseUrl('AUTHORIZATION_BASE_URL', 'http://localhost:8082');
const caseManagementBaseUrl = readBaseUrl('CASE_MANAGEMENT_BASE_URL', 'http://localhost:8083');
const caseSearchBaseUrl = readBaseUrl('CASE_SEARCH_BASE_URL', 'http://localhost:8084');
const caseSearchEnabled = readBoolean('ENABLE_CASE_SEARCH', true);
const pauseSeconds = readDecimal('ITERATION_PAUSE_SECONDS', 1, 0, 30);
const runId = readRunId();

const failureRate = readDecimal('MAX_FAILURE_RATE', 0.01, 0, 1);
const minimumCheckRate = readDecimal('MIN_CHECK_RATE', 0.99, 0, 1);
const authorizationP95 = readInteger('AUTHORIZATION_P95_MS', 1500, 1, 60000);
const caseDetailP95 = readInteger('CASE_DETAIL_P95_MS', 1500, 1, 60000);
const searchP95 = readInteger('CASE_SEARCH_P95_MS', 2000, 1, 60000);

export const options = {
  scenarios: {
    local_smoke: {
      executor: 'constant-vus',
      vus: readInteger('SMOKE_VUS', 1, 1, 10),
      duration: readDuration('SMOKE_DURATION', '15s'),
      gracefulStop: '5s',
    },
  },
  thresholds: {
    checks: [`rate>=${minimumCheckRate}`],
    'http_req_failed{endpoint:authorization}': [`rate<=${failureRate}`],
    'http_req_duration{endpoint:authorization}': [`p(95)<${authorizationP95}`],
    ...(caseSearchEnabled
      ? {
          'http_req_failed{endpoint:case-search}': [`rate<=${failureRate}`],
          'http_req_duration{endpoint:case-search}': [`p(95)<${searchP95}`],
          'http_req_failed{endpoint:case-detail}': [`rate<=${failureRate}`],
          'http_req_duration{endpoint:case-detail}': [`p(95)<${caseDetailP95}`],
        }
      : {}),
  },
  noConnectionReuse: false,
  userAgent: 'TransactIQ-k6-portfolio-smoke/1.0',
};

function requireReady(name, baseUrl) {
  const response = http.get(`${baseUrl}/actuator/health/readiness`, {
    tags: { endpoint: 'readiness', service: name },
    responseType: 'none',
    timeout: '3s',
  });
  if (response.status !== 200) {
    fail(`${name} is not ready at its configured base URL (HTTP ${response.status})`);
  }
}

export function setup() {
  requireReady('authorization-service', authorizationBaseUrl);
  if (caseSearchEnabled) {
    requireReady('case-management-service', caseManagementBaseUrl);
    requireReady('case-search-service', caseSearchBaseUrl);
  }
  return { runId };
}

export default function (data) {
  group('authorization', () => {
    const requestId = uuidV4();
    const body = JSON.stringify({
      requestId,
      cardToken: __ENV.CARD_TOKEN || 'tok_A1B2C3D4',
      merchantId: `merchant-k6-${data.runId}-${__VU}-${__ITER}`.slice(0, 64),
      merchantCategoryCode: __ENV.MERCHANT_CATEGORY_CODE || '5411',
      amount: readDecimal('AUTHORIZATION_AMOUNT', 0.01, 0.01, 1000000),
      currency: __ENV.AUTHORIZATION_CURRENCY || 'EUR',
      country: __ENV.AUTHORIZATION_COUNTRY || 'DE',
      channel: __ENV.AUTHORIZATION_CHANNEL || 'ECOMMERCE',
      transactionTime: new Date().toISOString(),
    });
    const response = http.post(`${authorizationBaseUrl}/api/v1/authorizations`, body, {
      headers: {
        'Content-Type': 'application/json',
        'X-Request-Id': uuidV4(),
      },
      tags: { endpoint: 'authorization' },
      responseType: 'none',
      timeout: '5s',
    });
    check(response, {
      'authorization returns a completed or accepted response': (result) =>
        result.status === 200 || result.status === 202,
    });
  });

  if (caseSearchEnabled) {
    group('fraud case search', () => {
      const response = http.get(
        `${caseSearchBaseUrl}/api/v1/fraud-cases/search?sort=UPDATED_AT_DESC&pageSize=5`,
        {
          headers: { 'X-Request-Id': uuidV4() },
          tags: { endpoint: 'case-search' },
          responseType: 'text',
          timeout: '5s',
        },
      );
      let hasSafeListShape = false;
      let caseId = null;
      if (response.status === 200) {
        try {
          const parsed = response.json();
          hasSafeListShape = parsed !== null && Array.isArray(parsed.items);
          if (hasSafeListShape && parsed.items.length > 0) {
            const candidate = parsed.items[0].caseId;
            const caseIdPattern =
              /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/;
            if (typeof candidate === 'string' && caseIdPattern.test(candidate)) {
              caseId = candidate;
            } else {
              hasSafeListShape = false;
            }
          }
        } catch (_) {
          hasSafeListShape = false;
        }
      }
      check(response, {
        'fraud-case search returns HTTP 200': (result) => result.status === 200,
        'fraud-case search returns a list (which may be empty)': () => hasSafeListShape,
      });

      if (caseId !== null) {
        const detailResponse = http.get(
          `${caseManagementBaseUrl}/api/v1/fraud-cases/${encodeURIComponent(caseId)}`,
          {
            headers: { 'X-Request-Id': uuidV4() },
            tags: { endpoint: 'case-detail' },
            responseType: 'none',
            timeout: '5s',
          },
        );
        check(detailResponse, {
          'fraud-case detail returns HTTP 200': (result) => result.status === 200,
        });
      }
    });
  }

  sleep(pauseSeconds);
}
