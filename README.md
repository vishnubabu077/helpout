# Instant Help - backend

Java 21 + Spring Boot 3.3, PostgreSQL 16, Redis 7, Kafka (KRaft), JWT (Spring Security resource server), Flyway, Swagger UI.

Implemented so far: phone + OTP sign-in that issues a JWT, **create / read / cancel help requests**, and
**"ready to help" availability with nearest-helper matching** (helpers go available, a requester matches a task,
the nearest helper gets an offer and accepts or declines). Completion and payments are the next steps.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/v1/auth/otp/request` | public | Request a one-time code |
| POST | `/v1/auth/otp/verify` | public | Verify code, get JWT (creates account on first use) |
| GET | `/v1/me` | JWT | Current user |
| GET | `/v1/categories` | JWT | Supported task categories |
| POST | `/v1/tasks` | JWT | Create a help request (published as `OPEN`) |
| GET | `/v1/tasks/mine` | JWT | My requests, newest first (`?page=&size=`) |
| GET | `/v1/tasks/{id}` | JWT | One of my requests (404 if not yours) |
| POST | `/v1/tasks/{id}/cancel` | JWT | Cancel my request (OPEN / MATCHING / ASSIGNED); releases the helper |
| **POST** | **`/v1/helpers/me/availability`** | JWT | **Go available**: location, categories, max distance (409 `ALREADY_AVAILABLE`) |
| PUT | `/v1/helpers/me/availability/location` | JWT | Heartbeat + location update (404 `NOT_AVAILABLE` if not available) |
| DELETE | `/v1/helpers/me/availability` | JWT | Go offline, only from `AVAILABLE` (409 `HELPER_BUSY` otherwise) |
| GET | `/v1/helpers/me/availability` | JWT | My current availability (`OFFLINE` when none) |
| **POST** | **`/v1/tasks/{taskId}/match`** | JWT | **Reserve the nearest helper for my OPEN task** (404 `NO_HELPER_AVAILABLE`, 409 `TASK_NOT_OPEN`) |
| POST | `/v1/helpers/me/offers/current/accept` | JWT | Accept my offer (409 `OFFER_EXPIRED`, 404 `NO_CURRENT_OFFER`) |
| POST | `/v1/helpers/me/offers/current/decline` | JWT | Decline my offer: I become `AVAILABLE`, the task goes back to `OPEN` |
| POST | `/v1/admin/matching/rebuild` | ADMIN | Rebuild the Redis helper index from Postgres |
| GET | `/actuator/health` | public | Health |

Swagger UI: http://localhost:8080/swagger-ui.html - OpenAPI JSON: http://localhost:8080/v3/api-docs

## Run it

Prerequisites: Docker. For option A also Java 21 and Maven 3.9+.

### A. App on your machine, infrastructure in Docker (best for development)

```bash
docker compose up -d                       # PostgreSQL :5432, Redis :6379, Kafka :29092
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

### B. Everything in Docker

```bash
docker compose --profile app up --build    # PostgreSQL + Redis + Kafka + the app image
```

Stop with `docker compose --profile app down` (add `-v` to also delete the database volume).
Ports can be changed with `POSTGRES_PORT`, `REDIS_PORT`, `KAFKA_PORT` and `APP_PORT`.

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

## Ready to help: how availability and matching work

PostgreSQL is the source of truth. Redis is a rebuildable read cache used only to *find candidates*. The helper who
actually gets a task is decided by one atomic conditional `UPDATE` in Postgres, never by Redis. There are no dual
writes: Postgres changes and their Kafka events are tied together by a transactional outbox.

```
helper: POST /v1/helpers/me/availability          (and PUT .../location as a heartbeat)
   |
   v  one transaction
[Postgres] helper_availability row (version n)  +  outbox_event row
   |
   v  OutboxRelay (@Scheduled, FOR UPDATE SKIP LOCKED, waits for the Kafka ack, then sets published_at)
[Kafka] topic helper-availability, key = helperId, payload carries version n
   |
   v  consumer group helper-geo-projector
[Redis] Lua script ignores stale/duplicate versions
        GEO  helpers:geo:{category}   member = availabilityId
        hash helper:{availabilityId}  helperId, version, status, maxDistance, categories   (TTL = heartbeat timeout)
   |
   v
requester: POST /v1/tasks/{id}/match
   1. GEOSEARCH helpers:geo:{category} around the task, nearest first (radius + candidate count from config)
   2. drop candidates whose hash expired (lazy ZREM), the requester themselves, or whose maxDistance < distance
   3. nearest first, try to reserve in Postgres with ONE statement:
        UPDATE helper_availability SET status='OFFERED', current_task_id=:task,
               offer_expires_at=now()+:ttl, version=version+1
         WHERE id=:id AND status='AVAILABLE' AND last_heartbeat_at > now()-:timeout  RETURNING *
      first row back wins; in the same transaction: task -> MATCHING and a HelperOffered outbox event
   4. after commit: ZREM from Redis right away, push notification to the helper (logging stub in `local`)
   5. no candidate could be reserved -> 404 NO_HELPER_AVAILABLE, the task stays OPEN
   |
   v
helper: POST /v1/helpers/me/offers/current/accept   (conditional on OFFERED and not expired, else 409 OFFER_EXPIRED)
   -> availability ASSIGNED, task ASSIGNED with the helper as provider, HelperAssigned + TaskAssigned events
   or .../decline -> availability AVAILABLE (HelperAvailable event puts them back in Redis), task OPEN
```

Rules worth knowing:

- **Out-of-order safe:** every availability change increments `version`, every event carries it, and the Redis script
  ignores any event whose version is not newer. Events are keyed by `helperId`, so one helper's events stay in order
  on one partition. The last applied version is kept in `helper:{availabilityId}:version` (1 h) even after the helper
  is removed, so a late older event cannot bring them back.
- **Phones that die disappear on their own:** the Redis hash has a TTL (`app.matching.heartbeat-timeout`, refreshed by
  every heartbeat). Matching skips and lazily cleans candidates whose hash expired, and Postgres also requires a fresh
  `last_heartbeat_at` when reserving. No "unavailable" event is needed.
- **Sweeper** (every `app.matching.sweep-interval`, `SKIP LOCKED`, safe on several instances): offers past
  `offer_expires_at` are treated as declined (helper back to `AVAILABLE`, or `OFFLINE` if the heartbeat is stale too;
  task back to `OPEN`), and `AVAILABLE` rows with a stale heartbeat become `OFFLINE` with a `HelperUnavailable` event.
- **Task closed:** cancelling a task (and, later, completing it) publishes a `TaskClosedEvent`; the helper's row goes
  `OFFLINE` and the helper chooses to go available again explicitly.
- A helper heart-beating while `OFFERED` or `ASSIGNED` keeps the row fresh but is not a candidate (the event status is
  not `AVAILABLE`).
- The match response contains the helper's public profile (id, display name) and an approximate distance rounded up
  to 100 m, never their exact location.
- Events: `HelperAvailable`, `HelperLocationUpdated`, `HelperUnavailable`, `HelperOffered`, `HelperAssigned` on topic
  `helper-availability`; `TaskAssigned`, `TaskCancelled` on topic `task-events` (keyed by task id).
  Delivery is at-least-once; consumers dedupe by `version`.

### Rebuilding Redis

Redis can be lost at any time without losing data. It is rebuilt from the `AVAILABLE` rows with a fresh heartbeat:

- automatically at application startup (failures are logged, they do not stop the app), and
- on demand, safe while the system is running (rows go through the same version-checked script as Kafka events):

```bash
# needs a token of a user whose app_user.role is 'ADMIN'
curl -X POST http://localhost:8080/v1/admin/matching/rebuild -H "Authorization: Bearer $ADMIN_TOKEN"
# {"helpersIndexed": 12, "orphansRemoved": 1}
```

Promote a local user with `update app_user set role = 'ADMIN' where phone_e164 = '+15555550101';` and sign in again
(the role is read when the token is issued).

### Try the flow with curl

```bash
BASE=http://localhost:8080
login() {
  curl -s -X POST $BASE/v1/auth/otp/request -H 'Content-Type: application/json' -d "{\"phone\":\"$1\"}" > /dev/null
  curl -s -X POST $BASE/v1/auth/otp/verify -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$1\",\"code\":\"000000\"}" | jq -r .accessToken
}
HELPER=$(login +15555550111); REQUESTER=$(login +15555550112)

curl -s -X POST $BASE/v1/helpers/me/availability -H "Authorization: Bearer $HELPER" \
  -H 'Content-Type: application/json' \
  -d '{"latitude":54.6872,"longitude":25.2797,"categoryCodes":["SHOPPING"],"maxDistanceM":3000}'

TASK=$(curl -s -X POST $BASE/v1/tasks -H "Authorization: Bearer $REQUESTER" -H 'Content-Type: application/json' -d '{
  "title":"Help carrying groceries","categoryCode":"SHOPPING","address":"12 Example Street",
  "latitude":54.6890,"longitude":25.2810,"startTime":"2035-01-01T10:00:00Z","endTime":"2035-01-01T11:00:00Z",
  "offeredAmount":15.00}' | jq -r .id)

sleep 2   # outbox -> Kafka -> Redis takes a moment
curl -s -X POST $BASE/v1/tasks/$TASK/match -H "Authorization: Bearer $REQUESTER"
curl -s -X POST $BASE/v1/helpers/me/offers/current/accept -H "Authorization: Bearer $HELPER"
```

## Security model (current)

- Stateless: every protected call needs `Authorization: Bearer <JWT>`. HS256, issuer `instant-help`, 15 minute expiry, `sub` = user id, `roles` claim.
- The requester of a task and the helper of an availability always come from the token, never from the request body. Tasks are readable only by their owner (404 for others).
- OTP: stored as HMAC (not plain), 5 minute expiry, max 5 wrong attempts per code, max 5 code requests per phone per hour.
- The `local` profile sets a dev JWT secret and a fixed OTP `000000` and logs codes. It must never be used in production:
  - `JWT_SECRET` (32+ bytes) has no default outside `local` - the app refuses to start without it.
  - `LoggingOtpSender` and `LoggingOfferNotifier` exist only in `local`, so other profiles need real `OtpSender` (SMS) and `OfferNotifier` (push) beans to start.
  - With the `prod` profile active, startup fails if `OTP_FIXED_CODE` is set.
- Set `SWAGGER_ENABLED=false` to turn Swagger off in production.
- Errors use RFC 7807 problem JSON with a stable `code` field (`VALIDATION_FAILED`, `UNSUPPORTED_CATEGORY`, `TASK_TOO_SHORT`, `ALREADY_AVAILABLE`, `HELPER_BUSY`, `NO_HELPER_AVAILABLE`, `OFFER_EXPIRED`, ...).

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | localhost / instanthelp / instanthelp | |
| `JWT_SECRET` | none (`local` profile has a dev value) | 32+ bytes |
| `OTP_FIXED_CODE` | empty | dev only |
| `SWAGGER_ENABLED` | `true` | |
| `SPRING_PROFILES_ACTIVE` | none | use `local` for development |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | `kafka:9092` inside compose |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |

Business settings in `application.yml` (all overridable with the env var shown, or any Spring property mechanism):

| Key | Default | Env var | Meaning |
|---|---|---|---|
| `app.task.min-duration-minutes` | 30 | | taken from your sketch, confirm it is a real rule |
| `app.task.default-currency` | EUR | | |
| `app.matching.search-radius-m` | 5000 | `MATCHING_SEARCH_RADIUS_M` | GEOSEARCH radius around the task |
| `app.matching.candidate-count` | 10 | `MATCHING_CANDIDATE_COUNT` | candidates read from Redis per match |
| `app.matching.offer-ttl` | 30s | `MATCHING_OFFER_TTL` | time a helper has to accept |
| `app.matching.heartbeat-timeout` | 90s | `MATCHING_HEARTBEAT_TIMEOUT` | Redis TTL and Postgres freshness window |
| `app.matching.sweep-interval` | 5s | `MATCHING_SWEEP_INTERVAL` | offer-expiry / stale-heartbeat sweeper period |
| `app.outbox.poll-interval` | 500ms | `OUTBOX_POLL_INTERVAL` | outbox relay period |
| `app.outbox.batch-size` | 100 | `OUTBOX_BATCH_SIZE` | rows published per batch |

## Tests

`mvn verify` runs the integration tests against throwaway PostgreSQL, Kafka (`apache/kafka`) and Redis containers started
by Testcontainers (needs Docker running; the first run pulls the images). They cover every endpoint, the Kafka -> Redis
projection (ordering, duplicates, stale versions, TTL expiry, rebuild), the offer sweeper, and a concurrency test where
several requesters match at the same instant and each helper is reserved exactly once.

## Project layout

```
src/main/java/com/instanthelp
  config/     AppProperties, SecurityConfig (JWT), OpenApiConfig, KafkaConfig, StartupChecks
  common/     exceptions, RFC 7807 handler, helpers
  identity/   OTP login, token issuing, /v1/me
  task/       help requests: entity, service, controller, categories, cancel
  helper/     availability (Postgres), offers accept/decline, sweeper, offer notifier
  matching/   Redis GEO index, Kafka projector, rebuild, nearest-helper matching
  outbox/     transactional outbox writer and Kafka relay
src/main/resources
  application.yml, application-local.yml, db/migration (Flyway), redis/apply-helper-event.lua
```

## Next steps

1. Task completion (publish `TaskClosedEvent` with `COMPLETED`) and payments.
2. Let an offered helper read the offered task (today they only get its id), and a real FCM `OfferNotifier`.
3. Refresh tokens (rotating, stored hashed, device-bound) and logout.
4. Real SMS `OtpSender` and per-IP rate limiting.
5. Approximate vs exact location: store both, reveal the exact address only after assignment.
6. Purge old published `outbox_event` rows.
