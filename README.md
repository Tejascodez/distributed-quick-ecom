# IMS — Quick-Commerce Platform

Microservices for a 10-minute delivery platform: geospatial dark-store routing, Redis/Lua stock
reservations, an orchestrated checkout saga with a transactional outbox, rider dispatch, and
real-time order tracking.

## Stack (stable versions, Oct 2026)

| Layer | Version |
|---|---|
| Java (target) | 21 LTS |
| Spring Boot | 4.1.2 (Spring Framework 7, Hibernate 7, Jackson 3) |
| Uber H3 | 4.5.1 |
| PostgreSQL + PostGIS | Neon (serverless Postgres) + PostGIS |
| Redis | 8.6.8 |
| Apache Kafka | 4.3.2 (KRaft, no ZooKeeper) |
| Debezium Connect | 3.6.3.Final |
| Envoy | 1.39.6 |
| Kafka UI (kafbat) | 1.5.0 |

## Services & ports

| Service | Port | Owns |
|---|---|---|
| Envoy gateway | **8000** (admin 9901) | routing, JWT validation (JWKS), rate limiting |
| user-auth-service | 8101 | `auth_db.users`, JWT issuing, `/.well-known/jwks.json` |
| dark-store-service | 8102 | `store_db`: dark_stores (PostGIS + H3), categories, products |
| order-service | 8103 | `order_db`: orders, order_items, outbox_events; saga orchestrator |
| inventory-service | 8104 | `inventory_db.store_inventories` + Redis sellable counters |
| delivery-service | 8105 | `delivery_db`: delivery_partners, delivery_assignments; Redis GEO |
| notification-service | 8106 | STOMP WebSocket `/ws`, push |
| Postgres | Neon | one project, one database per service |
| Redis / Kafka | 6379 / 9092 | |
| Debezium Connect / Kafka UI | 8083 / 8090 | |

## Run locally

```bash
cp .env.example .env                        # fill in Neon connection + secrets
./infra/neon/setup.sh                       # once: creates databases, checks logical replication
docker compose up -d --wait                 # Redis, Kafka, Debezium, Envoy
./mvnw package -DskipTests                  # build all modules
java -jar user-auth-service/target/user-auth-service-0.1.0-SNAPSHOT.jar &
java -jar dark-store-service/target/dark-store-service-0.1.0-SNAPSHOT.jar &
java -jar order-service/target/order-service-0.1.0-SNAPSHOT.jar &
java -jar inventory-service/target/inventory-service-0.1.0-SNAPSHOT.jar &
java -jar delivery-service/target/delivery-service-0.1.0-SNAPSHOT.jar &
java -jar notification-service/target/notification-service-0.1.0-SNAPSHOT.jar &
./infra/debezium/register.sh                # once, after order-service has migrated order_db
```

Run the jars from the repo root: services load secrets from `.env` via `spring.config.import`. Real
environment variables override `.env`, so deployments inject them normally. Debezium reads the DB credentials
from its own container env (`${env:DB_PASSWORD}`), so they never pass through the Connect REST API.

### Neon setup (once)

1. Create a Neon project. In **Project settings → Logical Replication**, turn it on (Debezium needs it; it
   can't be turned off again).
2. Copy the connection details from **Connect** into `.env`. Use the **direct** host, not the `-pooler` one.
   Debezium's replication connection and Flyway's migration locks don't work through PgBouncer.
3. Run `./infra/neon/setup.sh`.

Flyway creates each service's tables on first start. Tip: give each developer their own Neon branch so test
data doesn't collide.

## Payment webhook

`POST /api/v1/payments/webhook` skips JWT auth (the gateway is not a user) and is authenticated by HMAC instead:

```
X-Webhook-Timestamp: <unix seconds>
X-Webhook-Signature: hex(HMAC-SHA256(PAYMENT_WEBHOOK_SECRET, timestamp + "." + rawBody))
```

Requests with a missing or wrong signature, or a timestamp more than 5 minutes old (replays), get `401`.

Body:

```json
{"orderId": "<uuid>", "gatewayReference": "pay_123", "success": true, "amount": 224.00}
```

`amount` (what the gateway captured) is required when `success` is true. Each `gatewayReference` is applied once,
so redelivered webhooks are ignored. A captured payment that can't pay for the order is refunded via
`payment-refund-requested`: the wrong `amount` (the order stays unpaid so the customer can pay again), a second
payment for an already-paid order, or a payment for a cancelled order. A failed attempt never cancels an order
whose payment already succeeded.

All client traffic goes through `http://localhost:8000`. Get a token from
`POST /api/v1/auth/register` or `/login` and send `Authorization: Bearer <token>`. Envoy validates it and
forwards the user id to services as `X-User-Id`.

## Users & auth

| Endpoint | Who | Notes |
|---|---|---|
| `POST /api/v1/auth/register`, `/login` | public | returns `accessToken` (15 min), `refreshToken` (30 days) |
| `POST /api/v1/auth/refresh` | public | rotates the refresh token; reusing an old one revokes all sessions |
| `POST /api/v1/auth/logout` | public | ends one session (access token lives until expiry) |
| `GET / PATCH /api/v1/users/me` | any user | profile; phone is the login id and not editable |
| `DELETE /api/v1/users/me/sessions` | any user | log out everywhere |
| `/api/v1/users/me/addresses` (+ `/{id}`, `/{id}/default`) | any user | first address becomes default |
| `PUT /api/v1/users/{id}/role` | ADMIN | applies on the user's next login/refresh |

Roles: `CUSTOMER` (default), `STORE_OPS`, `RIDER`, `ADMIN`. Set `BOOTSTRAP_ADMIN_PHONE` in `.env` to
promote an existing account to the first admin at startup. Controllers take a `Caller` parameter
(`common/.../security`) built from the `X-User-Id` / `X-User-Role` headers Envoy derives from the JWT;
Envoy overwrites any client-sent copies. Services trust these headers, so they must only be reachable via Envoy.

Checkout requires a delivery address owned by the caller that lies inside the chosen store's radius.

## Tests

```bash
./mvnw verify          # needs Docker running (Testcontainers)
```

- `common`: `Caller` role checks and header parsing (unit).
- `user-auth-service`: signing-key loading (unit), plus integration tests that run the service over HTTP
  against throwaway Postgres + Redis containers, never Neon. Each request is verified against the
  service's JWKS and forwarded with Envoy's headers, so auth, roles, refresh rotation and addresses are
  tested end to end. Containers start once per JVM and are shared by all test classes.
- `inventory-service`: integration tests against Postgres + Redis + Kafka containers: reservation paths and
  redelivery, the reconciler, 500 buyers racing for 10 units, and the saga over real Kafka including the DLT.
- `order-service`: webhook HMAC verification (unit), plus integration tests against Postgres + Redis + Kafka
  containers with the catalog and address clients mocked: every saga path including out-of-order and duplicate
  payments, refunds and the amount check (asserted on `outbox_events`), racing inventory/payment replies,
  50 parallel checkouts with one Idempotency-Key, the signed webhook over HTTP, the timeout job, and the DLT.
- `delivery-service`: integration tests against Postgres + Redis + Kafka containers: three dispatchers racing
  over 100 orders and 10 constantly pinging riders (no rider double-booked), claim/ping/offline/stale-rider
  races, Redis losing busy markers, cancellations before and after assignment and pickup, a starved store,
  the rider endpoints over HTTP, and order-paid / order-cancelled over Kafka including the DLT.

## Checkout saga

```
POST /api/v1/orders (Idempotency-Key)
  order-service ── outbox ──> order-created
  inventory-service: reserve.lua (all-or-nothing) ──> inventory-reserved | inventory-rejected
POST /api/v1/payments/webhook
  order-service ── outbox ──> payment-success | payment-failed
  order-service: reserved + paid ──> PAID ──> order-paid-event
     inventory-service: confirm.lua + Postgres write-behind
     delivery-service: queue -> nearest idle rider (GEOSEARCH + claim.lua) ──> rider-assigned
     notification-service: picker dashboard + customer WebSocket
  Any failure / 10-min timeout ──> CANCELLED ──> order-cancelled ──> release.lua
     delivery-service: drop from queue, free the rider if not picked up yet
     paid already ──> payment-refund-requested
```

Every status change also emits `order-status-changed` for notifications.

### Topics
`order-created`, `inventory-reserved`, `inventory-rejected`, `payment-success`, `payment-failed`,
`payment-refund-requested`, `order-paid-event`, `order-cancelled`, `rider-assigned`, `order-status-changed`. See `common/.../Topics.java`.

## Design notes

- **Inventory**: Redis holds `sellable = available_stock - reserved_stock`. Keys use a `{storeId}` hash tag,
  so a multi-SKU Lua reservation stays on one Redis Cluster slot. The `stock_reservations` ledger in Postgres
  records every hold (HELD / CONFIRMED / RELEASED); each step updates it so that Kafka redelivery repairs a
  half-applied reserve, confirm or release. Holds expire after 15 minutes, longer than the 10-minute checkout
  timeout, so order-service decides cancellations and the sweeper is only a backstop. `StockReconciler`
  checks Redis and Postgres against the ledger every 5 minutes (report-only unless `ims.reconcile.repair`).
- **Outbox**: order-service never writes to Kafka directly. Debezium's EventRouter routes rows by
  `event_type` (= topic name), keyed by `aggregate_id` (= order id), which keeps per-order ordering.
- **Concurrency**: `orders.version` (optimistic locking) serializes concurrent saga replies; conflicts are
  retried. Records that can never succeed (malformed, unknown order) go to `<topic>-dlt` in order-service and
  inventory-service.
- **Rider pool**: online, idle riders live in a Redis GEO set. `claim.lua` takes a rider out of the pool and
  sets their busy marker in one step, and `ping.lua` only re-adds a rider who is online and not busy, so a
  location ping racing a claim can't double-book anyone. A claim's marker has a 2-minute TTL until its
  assignment commits. Riders silent for 60s leave the pool. Postgres is the source of truth for busy riders
  (a partial unique index allows one open assignment per rider), and markers are rebuilt from it at startup
  and every 15s. The dispatch queue is drained per store, so a store with no riders doesn't block the rest.
- **Geo**: `ST_DWithin` on a geography GiST index for "which store serves me". Stores also carry an H3
  res-8 cell for cell-based partitioning and caching.

## Known gaps (next steps)

- Push uses a logging stub; wire up the Firebase Admin SDK when credentials are available.
- Rider matching is greedy nearest-first per batch; replace with min-cost matching.
- Pickup and delivery don't update the order's status in order-service yet (ops set it), and a rider whose
  assignment is cancelled isn't notified in-app.
- inventory-service and delivery-service publish to Kafka directly (not via an outbox). inventory-service waits
  for the broker ack and relies on idempotent redelivery instead.
- Nothing consumes `payment-refund-requested` yet; a payment service would call the gateway's refund API.
- Cancelling an order after picking doesn't return its (already deducted) stock; restock it manually.
- Auth signing key is generated per boot (dev only). Load it from a secret store in real deployments.
- No product-admin API yet: seed `store_db.products` with SQL.
