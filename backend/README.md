# PayFlow backend

Ktor service for authenticated payment initiation, Stripe PaymentIntent creation, status reconciliation, and signed webhook processing. PostgreSQL is the source of durable order and attempt state; Stripe is the payment provider of record.

## Local setup

1. Copy `.env.example` to `.env` and set `STRIPE_SECRET_KEY` to the newly rotated test key. Do not put it in the Android app, project Gradle files, or chat.
2. Start the webhook forwarder and copy its signing secret into `STRIPE_WEBHOOK_SECRET` in `.env`:

   ```sh
   stripe listen --forward-to localhost:8080/v1/webhooks/stripe
   ```

3. Generate a local-only bearer token and put the same value in `PAYFLOW_DEV_BEARER_TOKEN` in `backend/.env` and `PAYFLOW_DEV_BEARER_TOKEN` in `~/.gradle/gradle.properties`:

   ```sh
   openssl rand -hex 32
   ```

4. Start PostgreSQL and the service:

   ```sh
   docker compose --env-file backend/.env up -d payflow-postgres
   ./backend/run-local.sh
   ```

Flyway creates the payment tables and inserts a demo order only when `PAYFLOW_ENV=local`. The backend listens on port `8080` by default (`PAYFLOW_PORT`). For Android emulator testing, set `PAYFLOW_API_BASE_URL=http://10.0.2.2:8080/` in `~/.gradle/gradle.properties`; Stripe CLI forwards webhook events to `localhost:8080`. Keep these port settings aligned if you change the backend port. Cleartext is allowed only for that host bridge in the debug variant; the release variant and backend deployment require TLS. The local bearer token is debug-only and release builds do not embed it. `run-local.sh` reads dotenv values without evaluating them as shell code or echoing them.

For PostgreSQL-backed integration tests, set `PAYFLOW_TEST_DATABASE_URL`, `PAYFLOW_TEST_DATABASE_USER`, and `PAYFLOW_TEST_DATABASE_PASSWORD`. The GitHub Actions workflow starts PostgreSQL automatically; without `PAYFLOW_TEST_DATABASE_URL`, the integration test class is skipped locally.

## Production configuration

Production requires OIDC JWT verification (`PAYFLOW_AUTH_MODE=oidc`) with HTTPS issuer, audience, and JWKS URL settings. It rejects local bearer auth and test-mode Stripe keys. Set database TLS to `sslmode=verify-full`. Provide Stripe secrets through the deployment secret manager, never through source, container image build arguments, request parameters, or logs.

Payment creation locks the customer's payable order, reserves one active attempt per order, and sends an owner-scoped hash of the request idempotency key to Stripe. A timeout leaves the attempt reconcilable and retries reuse Stripe's idempotency record. The Stripe webhook route verifies the signature against the raw request body, deduplicates event IDs, applies only valid lifecycle transitions, and marks an order paid only after a verified success event.
