# Project context

- Project name: TransactIQ.
- It is a personal, public portfolio project.
- It models a real-time transaction intelligence platform with fraud detection and a future AI fraud-analyst copilot.
- All transactions, card tokens, customers, merchants, cases, and operational data must be synthetic.
- Never introduce proprietary employer code, configuration, naming, credentials, data, or internal documentation.

# Collaboration rules

- Before implementing a business rule, explain it in plain language with one concrete transaction example.
- Explicitly list assumptions when requirements are unclear.
- Do not invent payment or fraud-domain behavior silently.
- Ask for clarification when an assumption would materially affect business behavior or architecture.
- Work in small, reviewable vertical slices.
- Keep changes limited to the requested scope.
- Do not perform unrelated refactoring.
- Do not stage, commit, push, publish, or create pull requests unless explicitly requested.
- Never add secrets, credentials, real card numbers, personal data, or production endpoints.

# Engineering direction

- Target Java 21 for Java services.
- Use Kotlin only in modules explicitly designated for Kotlin.
- Use a Gradle multi-module build with the Gradle Wrapper.
- Use Spring Boot and hexagonal architecture where they provide clear value.
- Keep domain logic independent from Spring, persistence, messaging, and AI providers.
- Use BigDecimal and an explicit ISO currency code for monetary values.
- Design event consumers for idempotency and at-least-once delivery.
- Do not add infrastructure components until required by an approved vertical slice.
- Do not add AI functionality until the underlying non-AI business workflow exists and is tested.

# Quality and verification

- Every behavior change requires appropriate automated tests.
- Prefer the smallest relevant test suite during iteration.
- Run relevant verification commands before claiming completion.
- Report exact commands and whether they passed or failed.
- If verification cannot run, explain why.
- Review the final diff for unnecessary changes.
- Keep README, business documentation, API contracts, and ADRs synchronized with material behavior or architectural decisions.
- Prefer readable code and explicit domain terminology over unnecessary abstraction.

# Current stage

- The repository is in the discovery and foundation stage.
- No production architecture decision is final merely because it appears in an initial proposal.
- Do not generate the complete platform or multiple services in a single task.
- The first implementation milestone will later be defined as a narrow end-to-end transaction flow.
