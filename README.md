# PayFlow

PayFlow is a Kotlin Multiplatform payment demo with an Android checkout built around Stripe PaymentSheet. It demonstrates a server-authoritative payment lifecycle, safe retries, 3DS handling, webhook reconciliation, and transaction history.

## Set up a local checkout

### Requirements

- Android Studio with Android SDK Platform 37 and JDK 21
- Docker Desktop with Docker Compose
- A Stripe sandbox and the [Stripe CLI](https://docs.stripe.com/cli/install)

### 1. Configure Stripe and the backend

In your Stripe **sandbox**, open **Developers → API keys** and copy the sandbox publishable key (`pk_test_…`) and secret key (`sk_test_…`). Use keys from the same sandbox. The secret key stays on the backend; never commit it or put it in the Android app. [Stripe API key guide](https://docs.stripe.com/keys)

From the project root:

```bash
cp backend/.env.example backend/.env
```

Edit `backend/.env` and set `STRIPE_SECRET_KEY`. Generate a local-only bearer token:

```bash
openssl rand -hex 32
```

Set its output as `PAYFLOW_DEV_BEARER_TOKEN` in `backend/.env`. Keep this token private and use the same value in the Android Gradle properties in step 3.

### 2. Start the webhook listener and backend

Authenticate Stripe CLI once, then keep this listener running in its own terminal. Copy its `whsec_…` output into `STRIPE_WEBHOOK_SECRET` in `backend/.env`:

```bash
stripe login
stripe listen --forward-to localhost:8080/v1/webhooks/stripe
```

In another terminal, start PostgreSQL and the backend:

```bash
docker compose --env-file backend/.env up -d payflow-postgres
./backend/run-local.sh
```

The local backend listens on port `8080` by default. If you change `PAYFLOW_PORT`, update the Stripe forwarding URL and Android API URL to match.

### 3. Configure the Android app

Create or edit `~/.gradle/gradle.properties` and add these local properties. Use your sandbox publishable key and the same development bearer token from step 1:

```properties
PAYFLOW_API_BASE_URL=http://10.0.2.2:8080/
STRIPE_PUBLISHABLE_KEY=pk_test_...
PAYFLOW_DEV_BEARER_TOKEN=<same-local-token-as-backend>
```

`10.0.2.2` is the Android emulator's route to services on your computer. On a physical device, use your computer's reachable LAN address and ensure the device can access the backend.

### 4. Build and launch

Open the project root in Android Studio, sync Gradle, start an emulator, and run the `androidApp` configuration. Or install from a terminal:

```bash
./gradlew :androidApp:installDebug
```

Use a Stripe test card such as `4242 4242 4242 4242`, any future expiry date, and any CVC. Test-mode payments do not move real money. For lifecycle and backend tests:

```bash
./gradlew :shared:allTests :androidApp:testDebugUnitTest :backend:test
```

## Project overview

- **`shared`** — Kotlin Multiplatform payment models, lifecycle rules, use cases, and provider contracts, independent of Android and Stripe SDKs.
- **`androidApp`** — Jetpack Compose, Navigation Compose, ViewModels/StateFlow, Hilt, Retrofit, and Stripe PaymentSheet. Includes Checkout and Activity screens.
- **`backend`** — Ktor API, PostgreSQL persistence, Stripe PaymentIntents, idempotency, authenticated status/history APIs, and signed webhook processing.

The backend owns order pricing and payment state. The app never stores card numbers; it reconciles payment status with the backend and prevents duplicate submissions. Production use requires OIDC authentication, HTTPS, and managed server-side secrets; local bearer authentication and test keys are for development only.

## Screenshots

<p align="center">
  <img src="docs/screenshots/checkout.png" alt="PayFlow checkout screen" width="32%" />
  <img src="docs/screenshots/payment-sheet.png" alt="Stripe PaymentSheet with available payment methods" width="32%" />
  <img src="docs/screenshots/activity.png" alt="PayFlow payment activity screen" width="32%" />
</p>

## Keep credentials out of Git

`backend/.env`, `~/.gradle/gradle.properties`, and local Android SDK configuration contain development-only values and must not be committed. The test publishable key is intended for client-side use; Stripe secret keys and webhook signing secrets must remain private. If a secret is exposed, rotate it in Stripe and update the local backend configuration.
