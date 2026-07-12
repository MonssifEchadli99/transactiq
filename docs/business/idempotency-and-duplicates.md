# Idempotency and Duplicate Requests

## Same requestId with identical request data

- Process the authorization only once.
- If the original request is `PENDING`, do not start another authorization workflow.
- If the original request is completed, return its recorded outcome.
- Never apply a balance operation twice.
- Never create duplicate fraud cases.

Example: Synthetic request `AUTH-10001` was declined and associated with fraud case `CASE-9001`. An identical duplicate returns the same decline and `CASE-9001`; it does not repeat processing or create another case.

## Same requestId with different request data

- Reject the later request with `REQUEST_ID_CONFLICT`.
- Do not perform fraud assessment.
- Do not create a fraud case.

`REJECTED` occurs before the request is accepted into the normal authorization lifecycle. It is not an additional transition from `PENDING`.

Example: Synthetic request `AUTH-10001` is first submitted for EUR 75 and later reused for EUR 200. The later request is rejected with `REQUEST_ID_CONFLICT` before authorization processing begins.

## Late result after timeout

- Keep the merchant-facing `TIMED_OUT` outcome unchanged.
- Do not convert it later into `APPROVED` or `DECLINED`.
- Record the late result for audit and observability.
- Do not automatically create a fraud case from the late result in V1.

Automated retry behavior remains outside V1 and will be defined separately.
