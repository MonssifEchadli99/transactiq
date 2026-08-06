# Local k6 performance smoke

This short smoke test exercises the authorization HTTP API, the fraud-case search read model, and
a fraud-case detail read when search returns a case.
It is a local operability check, **not a production benchmark or capacity claim**. It never calls
the investigation endpoints, MCP endpoint, or OpenAI.

The default profile uses one virtual user for 15 seconds with a one-second pause between
iterations. Every authorization has a fresh UUID, synthetic merchant identifier, transaction
timestamp, and request correlation ID. The default amount is EUR 0.01 against the repository's
synthetic funded-card fixture, which keeps repeated local runs conservative.

## Prerequisites and run

Start the relevant TransactIQ services through the documented demo before running the smoke. The
script performs bounded three-second readiness requests and stops with a clear error if a required
service is unavailable. It does not start, stop, or reset the stack.

With a local k6 installation:

```powershell
k6 run performance/k6/smoke.js
```

The defaults match the demo profile: authorization at `http://localhost:8082`, case management at
`http://localhost:8083`, and case search at `http://localhost:8084`. An authorization-only check is
available when the case services are not running:

```powershell
$env:ENABLE_CASE_SEARCH = 'false'
$env:SMOKE_DURATION = '10s'
k6 run performance/k6/smoke.js
```

To use the pinned official Docker image after the default Compose demo is running, attach k6 to
the Compose network and use the services' internal addresses. This keeps application ports bound
to localhost on the host while making them reachable from the smoke container:

```powershell
docker run --rm --network transactiq_default `
  -e AUTHORIZATION_BASE_URL=http://authorization-service:8080 `
  -e CASE_MANAGEMENT_BASE_URL=http://case-management-service:8080 `
  -e CASE_SEARCH_BASE_URL=http://case-search-service:8080 `
  -v "${PWD}/performance/k6:/scripts:ro" `
  grafana/k6:2.1.0 run /scripts/smoke.js
```

`transactiq_default` is the network name created when the demo runs from this repository with the
default Compose project name. Supply the corresponding network name if `COMPOSE_PROJECT_NAME` was
overridden.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `AUTHORIZATION_BASE_URL` | `http://localhost:8082` | Authorization service HTTP origin |
| `CASE_MANAGEMENT_BASE_URL` | `http://localhost:8083` | Case-management service HTTP origin |
| `CASE_SEARCH_BASE_URL` | `http://localhost:8084` | Case-search service HTTP origin |
| `ENABLE_CASE_SEARCH` | `true` | Set to `false` to disable both case read paths |
| `SMOKE_VUS` | `1` | Virtual users; bounded to 1–10 |
| `SMOKE_DURATION` | `15s` | k6 duration using `ms`, `s`, or `m` |
| `ITERATION_PAUSE_SECONDS` | `1` | Pause between iterations, 0–30 seconds |
| `AUTHORIZATION_AMOUNT` | `0.01` | Synthetic amount, bounded to 0.01–1,000,000 |
| `CARD_TOKEN` | `tok_A1B2C3D4` | Existing synthetic local fixture only |
| `MERCHANT_CATEGORY_CODE` | `5411` | Synthetic four-digit MCC |
| `AUTHORIZATION_CURRENCY` | `EUR` | ISO currency code |
| `AUTHORIZATION_COUNTRY` | `DE` | ISO country code |
| `AUTHORIZATION_CHANNEL` | `ECOMMERCE` | Existing channel contract value |
| `RUN_ID` | Generated | Optional sanitized run identifier |
| `MAX_FAILURE_RATE` | `0.01` | Maximum HTTP failure rate per tested endpoint |
| `MIN_CHECK_RATE` | `0.99` | Minimum successful-check rate |
| `AUTHORIZATION_P95_MS` | `1500` | Authorization p95 threshold in milliseconds |
| `CASE_DETAIL_P95_MS` | `1500` | Case-detail p95 threshold in milliseconds |
| `CASE_SEARCH_P95_MS` | `2000` | Search p95 threshold in milliseconds |

Only use synthetic values. k6's ordinary request metrics are collected, but this script does not
log response bodies or include request data in metric tags.

## Scope and interpretation

An empty fraud-case search result is valid: the script verifies the response shape without
requiring pre-seeded cases and skips the detail request. When a case exists, its server-returned
UUID is used for a read-only detail request to `case-management-service`; the script never
fabricates or mutates a case. The detail response body is discarded because it is unnecessary for
the smoke metrics. Local timings include developer-machine, container, JVM warm-up, and projection
effects and must not be presented as production latency, throughput, scalability, or
service-level objectives.
