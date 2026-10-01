# Instant Help - backend

Java 21 + Spring Boot 3.3, PostgreSQL 16, JWT (Spring Security resource server), Flyway, Swagger UI.

Implemented so far: phone + OTP sign-in that issues a JWT, and **create / read help requests** (`POST /v1/tasks`).
Claim, completion, payments and provider discovery are the next steps (see the design document).

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/v1/auth/otp/request` | public | Request a one-time code |
| POST | `/v1/auth/otp/verify` | public | Verify code, get JWT (creates account on first use) |
| GET | `/v1/me` | JWT | Current user |
| GET | `/v1/categories` | JWT | Supported task categories |
| **POST** | **`/v1/tasks`** | JWT | **Create a help request** (published as `OPEN`) |
| GET | `/v1/tasks/mine` | JWT | My requests, newest first (`?page=&size=`) |
| GET | `/v1/tasks/{id}` | JWT | One of my requests (404 if not yours) |
| GET | `/actuator/health` | public | Health |

Swagger UI: http://localhost:8080/swagger-ui.html - OpenAPI JSON: http://localhost:8080/v3/api-docs

## Run it

Prerequisites: Docker. For option A also Java 21 and Maven 3.9+.

### A. App on your machine, Postgres in Docker (best for development)

```bash
docker compose up -d                       # starts only PostgreSQL on localhost:5432
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

### B. Everything in Docker

```bash
docker compose --profile app up --build    # PostgreSQL + the app image
```

Stop with `docker compose --profile app down` (add `-v` to also delete the database volume).
Ports can be changed with `POSTGRES_PORT` and `APP_PORT`.

## Try it in Swagger UI

1. Open http://localhost:8080/swagger-ui.html
2. `POST /v1/auth/otp/request` with `{"phone": "+15555550101"}`
3. `POST /v1/auth/otp/verify` with the same phone and code `000000` (local profile) and copy `accessToken`
4. Click **Authorize**, paste the token (without the word "Bearer"), confirm
5. `POST /v1/tasks` and use the pre-filled example (change the dates if they are in the past)

## Try it with curl

```bash
BASE=http://localhost:8080
curl -s -X POST $BASE/v1/auth/otp/request -H 'Content-Type: application/json' \
  -d '{"phone":"+15555550101"}'

TOKEN=$(curl -s -X POST $BASE/v1/auth/otp/verify -H 'Content-Type: application/json' \
  -d '{"phone":"+15555550101","code":"000000"}' | jq -r .accessToken)

curl -s -X POST $BASE/v1/tasks -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "title": "Help carrying groceries upstairs",
  "description": "About 6 bags, third floor",
  "categoryCode": "SHOPPING",
  "address": "12 Example Street, Apt 5",
  "latitude": 54.6872,
  "longitude": 25.2797,
  "startTime": "2035-01-01T10:00:00Z",
  "endTime": "2035-01-01T11:00:00Z",
  "offeredAmount": 15.00
}'
```

## Security model (current)

- Stateless: every protected call needs `Authorization: Bearer <JWT>`. HS256, issuer `instant-help`, 15 minute expiry, `sub` = user id, `roles` claim.
- The requester of a task always comes from the token, never from the request body. Tasks are readable only by their owner (404 for others).
- OTP: stored as HMAC (not plain), 5 minute expiry, max 5 wrong attempts per code, max 5 code requests per phone per hour.
- The `local` profile sets a dev JWT secret and a fixed OTP `000000` and logs codes. It must never be used in production:
  - `JWT_SECRET` (32+ bytes) has no default outside `local` - the app refuses to start without it.
  - `LoggingOtpSender` exists only in `local`, so other profiles need a real `OtpSender` bean (SMS provider) to start.
  - With the `prod` profile active, startup fails if `OTP_FIXED_CODE` is set.
- Set `SWAGGER_ENABLED=false` to turn Swagger off in production.
- Errors use RFC 7807 problem JSON with a stable `code` field (`VALIDATION_FAILED`, `UNSUPPORTED_CATEGORY`, `TASK_TOO_SHORT`, ...).

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | localhost / instanthelp / instanthelp | |
| `JWT_SECRET` | none (`local` profile has a dev value) | 32+ bytes |
| `OTP_FIXED_CODE` | empty | dev only |
| `SWAGGER_ENABLED` | `true` | |
| `SPRING_PROFILES_ACTIVE` | none | use `local` for development |

Business settings in `application.yml`: `app.task.min-duration-minutes` (30, taken from your sketch, confirm it is a real rule) and `app.task.default-currency` (EUR).

## Tests

`mvn test` runs an integration test against a throwaway PostgreSQL started by Testcontainers (needs Docker running).

## Project layout

```
src/main/java/com/instanthelp
  config/     AppProperties, SecurityConfig (JWT), OpenApiConfig, StartupChecks
  common/     exceptions, RFC 7807 handler, helpers
  identity/   OTP login, token issuing, /v1/me
  task/       help requests: entity, service, controller, categories
src/main/resources
  application.yml, application-local.yml, db/migration/V1__init.sql (Flyway)
```

## Next steps

1. `POST /v1/tasks/{id}/claim` with the atomic conditional update + unique-active-claim index, and an `Idempotency-Key`.
2. Provider discovery: open-task feed by location (add PostGIS, or a bounding-box query first).
3. Refresh tokens (rotating, stored hashed, device-bound) and logout.
4. Real SMS `OtpSender` and per-IP rate limiting.
5. Approximate vs exact location: store both, reveal the exact address only after a claim.
