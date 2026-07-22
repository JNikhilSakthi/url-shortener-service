# URL Shortener Service

A production-style URL shortener that turns Redis into the workhorse behind every hot-path operation — caching, ID generation, click counting, and a live leaderboard — while MySQL stays the single durable system of record.

![Java](https://img.shields.io/badge/Java-25-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-brightgreen) ![Redis](https://img.shields.io/badge/Redis-7-red) ![MySQL](https://img.shields.io/badge/MySQL-8-blue) ![Docker](https://img.shields.io/badge/Docker-Compose-2496ED) ![Testcontainers](https://img.shields.io/badge/Testcontainers-integration%20tests-2496ED) ![License](https://img.shields.io/badge/License-MIT-lightgrey)

---

## Learning Track

**Learning Track:** `springboot-redis-cache-demo` (Project 6 of 17)
**Real-World Service Name:** `url-shortener-service`

This is part of a 17-project Spring Boot learning roadmap, one dependency/technology per project. This project's sole focus is **Redis** — no JWT, no Kafka, no Elasticsearch. Every design decision below exists to teach one of four core Redis patterns as clearly as possible.

---

## 1-3. Project Overview

### The problem

A URL shortener has two very different jobs happening at very different speeds:

1. **Writes are rare**: someone creates a short link maybe once every few seconds.
2. **Reads are relentless**: that same short link might get clicked thousands of times a minute, and every single click needs (a) the original URL back in microseconds and (b) a click recorded for analytics — without ever blocking the redirect on a database write.

A naive implementation hits MySQL on every redirect and does a `click_count = click_count + 1` `UPDATE` on every click. That works fine in a tutorial and falls over in production: a single hot short link (think a viral tweet) can turn into thousands of row-locking writes per second against the same row.

### Why Redis

Redis is uniquely good at exactly the four things this service needs, and this project deliberately keeps them as four *separate, independently inspectable* patterns rather than blending them into one "magic cache":

| Need | Redis primitive | Where it lives |
|---|---|---|
| Skip MySQL on a repeat read | String + TTL (cache-aside) | `url:cache:{code}` |
| Mint unique IDs without touching MySQL auto-increment | Atomic `INCR` counter | `url:id:sequence` |
| Count millions of clicks without hammering a MySQL row | Per-code counter + dirty set, batched flush | `clicks:pending:{code}`, `clicks:dirty-set` |
| Answer "what's trending" instantly | Sorted Set (`ZSET`) | `urls:leaderboard` |

### Where this shows up in the real world

- **Bit.ly / TinyURL-style services**: cache-aside on redirect is the textbook example used by nearly every "how does bit.ly scale" system-design writeup.
- **E-commerce product pages** (hence the "Product Cache" framing of this project): product detail lookups are cached exactly the same way — hot key, TTL, cache-aside, fall back to the DB of record on miss.
- **Distributed ID generation**: Redis `INCR`-based sequences (or Twitter Snowflake-style variants) are the standard way to generate unique IDs across multiple app instances without a database round trip or coordination service.
- **High-frequency counters**: social platforms buffer like/view counters in an in-memory store and flush deltas to the durable database on a schedule — precisely the "pending counter + dirty set + scheduled flush" pattern implemented here.
- **Trending/leaderboard features**: Redis ZSETs power "trending now," "top posts," and gaming leaderboards everywhere because `ZINCRBY`/`ZREVRANGE` are O(log N), not an O(N log N) sort recomputed on every request.
- **API rate limiting**: the fixed-window `INCR` + `EXPIRE` limiter here is the same technique used at the edge by API gateways (though production systems often prefer sliding-window or token-bucket for smoother behavior — see the Interview Prep section).

---

## 4. Architecture

### High-Level Design (HLD)

```
                                   ┌────────────────────────┐
                                   │        Client           │
                                   │ (browser / API caller)  │
                                   └───────────┬──────────────┘
                                               │
                             POST /api/urls    │   GET /{code}   GET /api/urls/{code}
                             GET /api/urls/popular          DELETE /api/urls/{code}
                                               │
                                               ▼
                              ┌───────────────────────────────┐
                              │   url-shortener-service        │
                              │   (Spring Boot, port 8080)     │
                              │                                 │
                              │  RedirectController              │
                              │  UrlShortenerController           │
                              │        │            │             │
                              │        ▼            ▼             │
                              │  UrlShortenerServiceImpl            │
                              │   ├─ IdGeneratorService              │
                              │   ├─ ClickTrackingService              │
                              │   └─ RateLimiterService                  │
                              └───────────┬───────────────┬───────────┘
                                          │               │
                            (cache, counters,      (durable record:
                             dirty set, ZSET,        url_mappings,
                             rate-limit key)          click_count)
                                          │               │
                                          ▼               ▼
                              ┌──────────────────┐   ┌──────────────┐
                              │      Redis        │   │    MySQL      │
                              │  (StringRedisTemplate)│  (JPA/Hibernate)│
                              └──────────────────┘   └──────────────┘

                              ┌───────────────────────────┐
                              │  redis-commander (:8081)    │
                              │  optional inspection UI only │
                              │  — not on the app's runtime  │
                              │       code path               │
                              └───────────────────────────┘
```

### Low-Level Design (LLD) — the four Redis patterns

**1. Cache-aside redirect (String + TTL)**
```
GET /{code}
   │
   ▼
GET url:cache:{code}  ──hit──▶ 302 redirect + recordClick()
   │ miss
   ▼
SELECT * FROM url_mappings WHERE short_code = ?
   │
   ├─ not found ─▶ 404
   ├─ expired    ─▶ 410
   └─ found ─▶ SET url:cache:{code} <url> EX <ttl> ─▶ 302 redirect + recordClick()

ttl = min(app.cache.url-ttl-seconds, secondsUntil(expiresAt))
```

**2. Distributed ID generation (`INCR` + Base62)**
```
INCR url:id:sequence            → 1, 2, 3, ...
value = sequence + offset(100000)   → never a trivially short code
shortCode = Base62Encoder.encode(value)   → e.g. "1w0J"
if shortCode already exists (collision) → retry, up to 5 attempts
```

**3. Click counting (pending counter + dirty set + scheduled flush)**
```
On every redirect:
  INCR clicks:pending:{code}
  SADD clicks:dirty-set {code}
  ZINCRBY urls:leaderboard 1 {code}

Every app.click-tracking.flush-interval-ms (@Scheduled fixedDelay):
  members = SMEMBERS clicks:dirty-set
  for each code in members:
      pending = GETDEL clicks:pending:{code}     (atomic read + clear)
      SREM clicks:dirty-set {code}
      if pending > 0:
          UPDATE url_mappings SET click_count = click_count + pending
                 WHERE short_code = {code}        (single atomic UPDATE, no full scan)
```

**4. Leaderboard (`ZSET`)**
```
GET /api/urls/popular?limit=N
   → ZREVRANGE urls:leaderboard 0 N-1 WITHSCORES
   → ranked list of {rank, shortCode, clicks}
```

**5. Rate limiting (fixed window)**
```
POST /api/urls
   → INCR ratelimit:create:{clientIp}
   → if count == 1: EXPIRE key app.rate-limit.window-seconds
   → if count > app.rate-limit.max-requests → 429 Too Many Requests
```

### Folder structure

```
url-shortener-service/
├── Dockerfile
├── docker-compose.yml
├── pom.xml
└── src/
    ├── main/java/com/medha/urlshortenerservice/
    │   ├── UrlShortenerServiceApplication.java
    │   ├── config/
    │   │   ├── RedisKeys.java                 # every Redis key pattern, in one place
    │   │   └── UrlShortenerProperties.java    # binds the app.* config tree
    │   ├── controller/
    │   │   ├── RedirectController.java        # GET /{shortCode}
    │   │   └── UrlShortenerController.java    # /api/urls/**
    │   ├── domain/
    │   │   └── UrlMapping.java                # JPA entity = MySQL system of record
    │   ├── dto/
    │   │   ├── CreateShortUrlRequest.java
    │   │   ├── ShortUrlResponse.java
    │   │   ├── UrlStatsResponse.java
    │   │   └── PopularUrlResponse.java
    │   ├── exception/
    │   │   ├── DuplicateAliasException.java
    │   │   ├── UrlExpiredException.java
    │   │   ├── UrlNotFoundException.java
    │   │   ├── RateLimitExceededException.java
    │   │   ├── ErrorResponse.java
    │   │   └── GlobalExceptionHandler.java
    │   ├── repository/
    │   │   └── UrlMappingRepository.java
    │   ├── service/
    │   │   ├── UrlShortenerService.java / Impl.java
    │   │   ├── IdGeneratorService.java        # Redis INCR sequence generator
    │   │   ├── ClickTrackingService.java      # pending counter + dirty set + ZSET + scheduled flush
    │   │   └── RateLimiterService.java        # fixed-window INCR/EXPIRE limiter
    │   └── util/
    │       └── Base62Encoder.java
    └── test/java/com/medha/urlshortenerservice/
        ├── integration/UrlShortenerIntegrationTest.java   # Testcontainers: MySQL + Redis, full HTTP flow
        ├── service/ClickTrackingServiceTest.java
        ├── service/RateLimiterServiceTest.java
        ├── service/UrlShortenerServiceImplTest.java
        └── util/Base62EncoderTest.java
```

### Request flow: creating and using a short URL

```
1. POST /api/urls {"originalUrl": "https://example.com/very/long/path"}
      → RateLimiterService.tryAcquire(clientIp)  [429 if exceeded]
      → IdGeneratorService.nextShortCode()         [INCR + Base62]
      → save UrlMapping row in MySQL
      → cacheUrl(): SET url:cache:{code} <url> EX ttl   (write-through: primes cache immediately)
      → 201 Created { shortCode, shortUrl, originalUrl, createdAt, expiresAt }

2. GET /{code}
      → cache hit  → 302 redirect, recordClick()
      → cache miss → MySQL lookup → repopulate cache → 302 redirect, recordClick()
      → expired    → 410 Gone
      → not found  → 404 Not Found

3. GET /api/urls/{code}     → stats = MySQL click_count + Redis pending clicks (not yet flushed)
4. DELETE /api/urls/{code}  → delete MySQL row + evict every Redis artifact for that code
5. GET /api/urls/popular    → ZSET leaderboard, top N
```

### Database design

Single table, MySQL, `ddl-auto: update` (no Flyway/Liquibase — kept intentionally simple to stay focused on Redis):

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT, PK, IDENTITY | surrogate key only; **not** used to generate short codes |
| `short_code` | VARCHAR(32), unique index `idx_url_mappings_short_code` | the public identifier |
| `original_url` | VARCHAR(2048) | target URL |
| `custom_alias` | BOOLEAN | true if the caller supplied their own code |
| `click_count` | BIGINT, default 0 | durable total; reconciled from Redis by the scheduled flush |
| `created_at` | TIMESTAMP, not updatable | set in `@PrePersist` |
| `expires_at` | TIMESTAMP, nullable | optional TTL for the mapping itself |

---

## 5. Tech Stack

| Layer | Technology | Purpose |
|---|---|---|
| Language / runtime | Java 25, Spring Boot 4.0.6 | application framework |
| Web | Spring Web (MVC) | REST controllers |
| Persistence | Spring Data JPA + Hibernate, MySQL 8 (`mysql-connector-j`) | durable system of record |
| Cache / data structures | Spring Data Redis (`StringRedisTemplate`, Lettuce client) | cache-aside, counters, sets, ZSET |
| Validation | Spring Boot Starter Validation (Jakarta Bean Validation, Hibernate Validator `@URL`) | request payload validation |
| Observability | Spring Boot Actuator | `/actuator/health`, `/actuator/metrics`, `/actuator/info` |
| Build | Maven, `spring-boot-maven-plugin` | packaging as an executable jar |
| Containerization | Docker (multi-stage), Docker Compose | local orchestration of app + MySQL + Redis + Redis Commander |
| Testing | JUnit 5, Mockito, AssertJ, Testcontainers (MySQL + generic Redis image) | unit + full-stack integration tests |

---

## 6. Configuration Explained

`src/main/resources/application.yml`, property by property:

```yaml
server:
  port: 8080
```
Standard embedded Tomcat port.

```yaml
spring:
  application:
    name: url-shortener-service
```
Used in logs/Actuator/metrics tagging.

```yaml
  datasource:
    url: jdbc:mysql://${DB_HOST:mysql}:${DB_PORT:3306}/${DB_NAME:urlshortener}?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: ${DB_USERNAME:urlshortener}
    password: ${DB_PASSWORD:urlshortener}
    driver-class-name: com.mysql.cj.jdbc.Driver
```
Every value is `${ENV_VAR:default}` so the exact same jar runs unmodified whether it's started locally (defaults point at `mysql`/`redis`, the Compose service names) or with env vars overridden in another environment. `createDatabaseIfNotExist=true` means the schema doesn't need to pre-exist for local/dev use.

```yaml
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 20000
```
Conservative pool sizing appropriate for a single-instance learning project — MySQL is not the bottleneck here (Redis absorbs the hot path), so the pool doesn't need to be large.

```yaml
  jpa:
    hibernate:
      ddl-auto: update
```
Auto-migrates the schema from the `UrlMapping` entity. Deliberately **not** Flyway/Liquibase — this project's teaching scope is Redis, not schema migration tooling; `update` keeps setup friction near zero.

```yaml
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQLDialect
    open-in-view: false
```
`open-in-view: false` avoids the lazy-loading-in-view anti-pattern (not that this project has many associations, but it's good hygiene). `show-sql` is off by default to keep logs quiet; `format_sql` only matters if you flip `show-sql` on.

```yaml
  data:
    redis:
      host: ${REDIS_HOST:redis}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 2000ms
```
Same env-var-overridable pattern as the datasource. A 2-second command timeout is intentionally short: on the hot redirect path, a hung Redis call should fail fast rather than hold up an HTTP thread.

```yaml
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
          max-wait: 2000ms
```
Lettuce is the default Spring Boot Redis client; pooling connections avoids per-command connection setup overhead. `min-idle: 2` keeps a couple of warm connections ready so the first request after idle time isn't slow.

```yaml
  jackson:
    datatype:
      datetime:
        write-dates-as-timestamps: false
```
`Instant` fields (`createdAt`, `expiresAt`) serialize as ISO-8601 strings in JSON responses instead of raw epoch millis arrays — much more readable in API responses/tests. (Spring Boot 4 ships Jackson 3, which moved `WRITE_DATES_AS_TIMESTAMPS` from `SerializationFeature` to `DateTimeFeature`, so the property moved from `spring.jackson.serialization.*` to `spring.jackson.datatype.datetime.*`; Jackson 3 also defaults to ISO-8601 already, so this is now mostly documentation of intent.)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
  health:
    redis:
      enabled: true
```
Exposes just enough Actuator surface for local operability (`/actuator/health`, `/actuator/metrics`, `/actuator/info`) without opening up every endpoint. `health.redis.enabled: true` makes `/actuator/health` explicitly report Redis connectivity, not just MySQL — important because this app is unusable without Redis.

```yaml
app:
  base-url: ${APP_BASE_URL:http://localhost:8080}
```
Used by `UrlShortenerServiceImpl.buildShortUrl()` to build the fully-qualified `shortUrl` field in API responses. In Compose it's set to `http://localhost:8080` (the URL the *browser*, not the app, will use).

```yaml
  cache:
    url-ttl-seconds: 3600          # how long a resolved short-code -> URL mapping lives in Redis
```
Bound to `UrlShortenerProperties.Cache.urlTtlSeconds`. Consumed in `UrlShortenerServiceImpl.cacheUrl()`, capped further by `Duration.between(now, expiresAt)` so a mapping never stays cached past its own expiry.

```yaml
  id-generator:
    sequence-key: "url:id:sequence"
    offset: 100000                  # ensures generated codes are never trivially short (e.g. "1")
```
Bound to `UrlShortenerProperties.IdGenerator`. `IdGeneratorService` adds this offset to every `INCR` result before Base62-encoding, so the very first codes minted aren't confusingly short single characters.

```yaml
  rate-limit:
    enabled: true
    max-requests: 20                # max short-URL creations
    window-seconds: 60              # ...per rolling window, per client IP
```
Bound to `UrlShortenerProperties.RateLimit`, consumed by `RateLimiterService`. Protects `POST /api/urls` from abuse (a 429 is thrown by `UrlShortenerController` via `RateLimitExceededException` when exceeded).

```yaml
  click-tracking:
    flush-interval-ms: 15000        # how often pending Redis click counters are flushed into MySQL
    leaderboard-size: 10            # default size for the "popular URLs" endpoint
```
Bound to `UrlShortenerProperties.ClickTracking`. `flush-interval-ms` drives the `@Scheduled(fixedDelayString = "${app.click-tracking.flush-interval-ms}")` job in `ClickTrackingService`. `leaderboard-size` is the default `limit` for `GET /api/urls/popular` when no query param is given.

```yaml
logging:
  level:
    com.medha.urlshortenerservice: INFO
    org.springframework.data.redis: WARN
```
Keeps the app's own logs informative while silencing Redis client chatter (connection pool noise at DEBUG would otherwise dominate the logs).

---

## 7. Project Structure Explained

| Path | Why it exists |
|---|---|
| `Dockerfile` | Multi-stage build: Maven builds the jar in a throwaway build stage; the runtime stage is a minimal `eclipse-temurin:25-jre-alpine` image running as a non-root `spring` user, with a `HEALTHCHECK` hitting `/actuator/health`. |
| `docker-compose.yml` | Wires up `mysql`, `redis`, `redis-commander` (inspection UI), and the `app` itself on one bridge network, with health-check-gated `depends_on` so the app doesn't start before its dependencies are ready. |
| `pom.xml` | Maven build: Spring Boot 4.0.6 parent, Java 25, web/JPA/Redis/validation/actuator starters (plus `spring-boot-starter-webmvc-test` for MockMvc in tests), MySQL driver, Testcontainers BOM for integration tests. |
| `.dockerignore` / `.gitignore` | Keep `target/`, IDE files, and the Maven wrapper jar out of the Docker build context and git history. |
| `src/main/resources/application.yml` | All runtime configuration — see section 6. |
| `UrlShortenerServiceApplication.java` | Entry point; `@EnableScheduling` turns on the click-flush job, `@ConfigurationPropertiesScan` picks up `UrlShortenerProperties` without needing an explicit `@EnableConfigurationProperties`. |
| `config/RedisKeys.java` | Every Redis key pattern used anywhere in the app, centralized so key-naming conventions never drift between classes and so a learner can grep one file to see the entire Redis key schema. |
| `config/UrlShortenerProperties.java` | Strongly-typed `@ConfigurationProperties(prefix = "app")` binding — every tunable in one Java object instead of scattered `@Value` annotations. |
| `controller/RedirectController.java` | The public-facing `GET /{shortCode}` redirect endpoint, deliberately kept separate from `/api/urls/**` so short codes at the root path never collide with management-API routes. |
| `controller/UrlShortenerController.java` | The management API: create, stats, delete, popular. Also where the per-request rate-limit check happens (via client IP / `X-Forwarded-For`). |
| `domain/UrlMapping.java` | The one JPA entity — MySQL's durable system of record for short-code → URL and total click count. |
| `dto/*.java` | Request/response records, kept separate from the entity so the API contract can evolve independently of the persistence model. |
| `exception/*.java` + `GlobalExceptionHandler.java` | Domain exceptions mapped centrally to HTTP status codes (404/410/409/429/400/500) with a uniform `ErrorResponse` JSON body. |
| `repository/UrlMappingRepository.java` | Spring Data JPA repository; notably `incrementClickCount` is a single atomic `UPDATE ... SET click_count = click_count + :increment`, never a read-modify-write, so concurrent flush cycles can't lose clicks. |
| `service/IdGeneratorService.java` | Redis `INCR`-based distributed sequence + Base62 encoding — collision-free short codes without relying on MySQL auto-increment. |
| `service/ClickTrackingService.java` | Owns all click-related Redis structures: pending counter, dirty set, leaderboard ZSET, and the `@Scheduled` flush job that reconciles Redis deltas into MySQL. |
| `service/RateLimiterService.java` | Fixed-window rate limiter (`INCR` + `EXPIRE`) protecting `POST /api/urls`. |
| `service/UrlShortenerService(Impl).java` | Orchestrates the whole domain flow: create (with cache priming), resolve+click (cache-aside), stats (MySQL total + Redis pending), delete (DB delete + full cache eviction). |
| `util/Base62Encoder.java` | Turns the monotonically increasing Redis sequence value into a compact, URL-safe short code (and back). |
| `test/.../integration/UrlShortenerIntegrationTest.java` | Testcontainers-backed (real MySQL + Redis images, no mocks) full HTTP flow: create → redirect → stats → delete, plus validation/duplicate-alias/not-found edge cases. |
| `test/.../service/*Test.java` | Mockito unit tests stubbing `StringRedisTemplate`'s `opsForValue`/`opsForSet`/`opsForZSet` to verify each Redis interaction (`INCR`, `SADD`, `ZINCRBY`, `GETDEL`, etc.) in isolation. |
| `test/.../util/Base62EncoderTest.java` | Encode/decode round-trip property test across representative and edge-case values, including `Long.MAX_VALUE`. |

---

## 8. Getting Started

### Prerequisites

- Docker + Docker Compose
- (Optional, for local dev outside Docker) Java 25 and Maven 3.9+

### Run everything with Docker Compose

```bash
git clone https://github.com/JNikhilSakthi/url-shortener-service.git
cd url-shortener-service

# Build the app image and start MySQL, Redis, Redis Commander, and the app
docker compose up --build

# ...or detached:
docker compose up --build -d

# Tail logs
docker compose logs -f app

# Stop everything
docker compose down

# Stop and wipe MySQL/Redis data volumes too
docker compose down -v
```

Once healthy:

| Service | URL |
|---|---|
| App | http://localhost:8080 |
| Health check | http://localhost:8080/actuator/health |
| Redis Commander (inspect Redis keys) | http://localhost:8081 |
| MySQL | localhost:3306 (`urlshortener` / `urlshortener`) |

### Run locally without Docker (app only)

Point the app at your own MySQL/Redis instances via env vars, then:

```bash
DB_HOST=localhost DB_PORT=3306 DB_NAME=urlshortener \
DB_USERNAME=urlshortener DB_PASSWORD=urlshortener \
REDIS_HOST=localhost REDIS_PORT=6379 \
mvn spring-boot:run
```

---

## 9. API Documentation

Base URL: `http://localhost:8080`

### Create a short URL

```
POST /api/urls
Content-Type: application/json
```

Request:
```json
{
  "originalUrl": "https://example.com/some/very/long/path",
  "customAlias": "my-alias",
  "expiresInDays": 30
}
```
All fields except `originalUrl` are optional. `customAlias` must match `^[a-zA-Z0-9_-]{3,20}$`; `expiresInDays` must be 1-365.

Response `201 Created`:
```json
{
  "shortCode": "my-alias",
  "shortUrl": "http://localhost:8080/my-alias",
  "originalUrl": "https://example.com/some/very/long/path",
  "createdAt": "2026-07-22T10:15:30Z",
  "expiresAt": "2026-08-21T10:15:30Z"
}
```

Errors: `400` (validation failure), `409` (custom alias already taken), `429` (rate limit exceeded — 20 requests/60s per client IP by default).

### Redirect (the actual short link)

```
GET /{shortCode}
```
Response: `302 Found` with `Location: <originalUrl>` header (cache-aside hit or miss, transparently). `404` if unknown, `410 Gone` if the mapping has expired.

### Get stats for a short code

```
GET /api/urls/{shortCode}
```

Response `200 OK`:
```json
{
  "shortCode": "my-alias",
  "shortUrl": "http://localhost:8080/my-alias",
  "originalUrl": "https://example.com/some/very/long/path",
  "customAlias": true,
  "createdAt": "2026-07-22T10:15:30Z",
  "expiresAt": "2026-08-21T10:15:30Z",
  "totalClicks": 128
}
```
`totalClicks` = MySQL's durable `click_count` **plus** any not-yet-flushed Redis pending clicks, so stats are always fresh even between flush cycles.

### Delete a short URL

```
DELETE /api/urls/{shortCode}
```
Response: `204 No Content`. Deletes the MySQL row and evicts every related Redis artifact (cache entry, pending counter, dirty-set membership, leaderboard entry). `404` if unknown.

### Popular URLs (leaderboard)

```
GET /api/urls/popular?limit=5
```
`limit` is optional; defaults to `app.click-tracking.leaderboard-size` (10).

Response `200 OK`:
```json
[
  { "rank": 1, "shortCode": "my-alias", "clicks": 128 },
  { "rank": 2, "shortCode": "abc123",   "clicks": 97 }
]
```

### Error format (all non-2xx responses)

```json
{
  "timestamp": "2026-07-22T10:20:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "No URL mapping found for short code 'unknown'",
  "path": "/api/urls/unknown",
  "details": []
}
```
`details` is populated with per-field messages on `400` validation failures.

---

## 10. Testing

```bash
# Unit tests only (fast, no Docker required)
mvn test -Dtest='!UrlShortenerIntegrationTest'

# Everything, including the Testcontainers integration test (requires a running Docker daemon)
mvn test
```

| Test class | What it covers |
|---|---|
| `Base62EncoderTest` | Encode/decode round-trip identity across zero, small values, powers of 62, and `Long.MAX_VALUE`; rejects negative input and invalid characters. |
| `RateLimiterServiceTest` | First request in a window sets the `EXPIRE`; subsequent requests under the limit don't reset it; requests over the limit are blocked; a disabled limiter always allows. |
| `ClickTrackingServiceTest` | `recordClick` hits `INCR`/`SADD`/`ZINCRBY` correctly; `flushPendingClicks` drains the dirty set and applies the delta via `incrementClickCount`, skipping empty/zero deltas; `topPopular` maps ZSET tuples into ranked DTOs, using the configured default size when no limit is given; `evictAll` removes every related key. |
| `UrlShortenerServiceImplTest` | Create (both generated and custom-alias codes) primes the cache; duplicate alias throws `409`; cache-hit and cache-miss redirect paths both record a click; expired mappings throw without recording a click; stats combine MySQL total + Redis pending; delete removes the DB row and evicts the cache; not-found paths throw `404`. |
| `UrlShortenerIntegrationTest` | Full stack against **real** MySQL + Redis (Testcontainers, no mocks): create → redirect (302 with correct `Location`) → stats (click count reflects the redirect) → delete → subsequent 404. Also covers invalid-URL validation and duplicate-custom-alias conflict via real HTTP (`MockMvc`). |

The integration test spins up `mysql:8.0` and `redis:7-alpine` containers via `@Container`/`@DynamicPropertySource`, and overrides `app.click-tracking.flush-interval-ms` to `1000` so the test doesn't have to wait long for the scheduled flush to matter (though the stats endpoint itself reads pending Redis clicks directly, so it's accurate even before a flush occurs).

---

## 11. Docker

### `Dockerfile` (multi-stage)

- **Stage 1 (`build`)**: `maven:3.9-eclipse-temurin-25`. Copies `pom.xml` first and runs `dependency:go-offline` before copying source, so dependency downloads are cached in their own Docker layer and only re-run when `pom.xml` changes — not on every source edit. Then `mvn clean package -DskipTests` builds the jar (tests run separately in CI/locally, not as part of the image build).
- **Stage 2 (`runtime`)**: `eclipse-temurin:25-jre-alpine` — JRE only, no JDK/Maven, for a small final image. Creates and switches to a non-root `spring` user (`addgroup`/`adduser`) before copying in the jar, so the container never runs as root. Exposes `8080`. `HEALTHCHECK` polls `/actuator/health` every 15s and greps for `"status":"UP"`. `JAVA_OPTS` is an empty-by-default env var so JVM flags (heap size, GC, etc.) can be injected at `docker run`/Compose time without rebuilding the image.

### `docker-compose.yml`

| Service | Image | Role |
|---|---|---|
| `mysql` | `mysql:8.0` | durable store; creates `urlshortener` DB/user on first boot; `mysqladmin ping` healthcheck gates the app's startup |
| `redis` | `redis:7-alpine` | cache/counters/leaderboard/rate-limiter; `--appendonly yes` for durability across restarts; `redis-cli ping` healthcheck |
| `redis-commander` | `rediscommander/redis-commander` | **optional** web UI (port 8081) for browsing the actual Redis keys this app creates (`url:cache:*`, `clicks:pending:*`, `clicks:dirty-set`, `urls:leaderboard`, `url:id:sequence`, `ratelimit:create:*`) — purely a learning/inspection aid, not on the app's runtime path |
| `app` | built from local `Dockerfile` | the Spring Boot service; `depends_on` both `mysql` and `redis` with `condition: service_healthy` so it never starts against a not-yet-ready dependency; all DB/Redis connection details and `APP_BASE_URL` are passed as environment variables, matching the `${VAR:default}` placeholders in `application.yml` |

All services share one bridge network (`url-shortener-net`) so they resolve each other by service name (`mysql`, `redis`) exactly as `application.yml`'s defaults expect. Named volumes (`mysql-data`, `redis-data`) persist data across `docker compose down` (but not `down -v`).

---

## 12. Interview Preparation

**Q: Why cache-aside instead of write-through or read-through for the URL lookup?**
Cache-aside (lazy loading) keeps the application in control of exactly what gets cached and when, and — critically here — lets the TTL be computed per-entry (`min(configured TTL, time until this specific mapping expires)`), which a generic read-through/write-through cache abstraction wouldn't easily support. The trade-off is the classic "thundering herd on a cold cache" risk, which in this project is small because TTL is set to 1 hour by default and creation already write-through-primes the cache.

**Q: Why not just use MySQL auto-increment for the short code?**
Auto-increment is fine for a single database instance, but ties ID generation to the primary datastore and doesn't scale if you ever read-replica or shard MySQL. A Redis `INCR` sequence is atomic, O(1), and — more importantly for a "Redis showcase" project — demonstrates the pattern used in real distributed-ID systems (Twitter Snowflake, Instagram's ID generation, etc. all lean on similar "single atomic counter, decorate the output" ideas). The trade-off: Redis becomes a hard dependency for URL creation (not just performance) — if Redis is down, no new short codes can be minted; reads also depend on Redis being reachable for the cache-aside GET, so in practice Redis unavailability degrades the whole app, not just writes.

**Q: Why buffer clicks in Redis instead of writing straight to MySQL on every click?**
A single popular link could receive thousands of clicks/minute; doing `UPDATE ... SET click_count = click_count + 1` that often against one row creates serious row-lock contention and I/O load on the database of record. Buffering in Redis (`INCR` on a String) turns "N clicks" into "N Redis ops (cheap, in-memory) + 1 MySQL write every flush interval" — the classic write-behind/buffering pattern.

**Q: Why a "dirty set" instead of just running `KEYS clicks:pending:*` on a schedule?**
`KEYS` (and even `SCAN`) over the whole keyspace is O(N) in the total number of keys Redis holds and can block or slow down a shared Redis instance in production. Maintaining an explicit `SADD`-populated set of "codes with pending clicks" turns the flush job into `SMEMBERS` (proportional only to codes with *actual* recent traffic) instead of a keyspace scan — this is the single most important operational lesson in this codebase.

**Q: Why `GETDEL` instead of `GET` then `DEL`?**
`GETDEL` (Redis 6.2+) atomically reads and clears the counter in one round trip. Doing `GET` then `DEL` as two separate commands leaves a race window: a click could land between the `GET` and the `DEL` and be silently lost when the counter is cleared. `GETDEL` closes that gap without needing a transaction or Lua script.

**Q: What happens if the scheduled flush job throws partway through?**
As written, `flushPendingClicks` iterates codes one at a time; if `incrementClickCount` throws for one code, that exception propagates and stops the loop for the remaining codes in that run (Spring's `@Scheduled` will log the exception and simply run again at the next interval — codes already `GETDEL`'d in that failed run whose MySQL write didn't happen represent a real click-loss edge case worth calling out in an interview: a production version would want per-code try/catch, or a Lua script per code, or at minimum metrics/alerting on flush failures).

**Q: Why is `click_count` incremented via `UPDATE click_count = click_count + ?` rather than reading the row, adding in Java, and saving it back?**
The atomic `UPDATE ... SET x = x + ?` is a single round trip that MySQL's storage engine executes under a row lock, so two concurrent flush cycles (e.g. after a rolling deploy leaves two instances briefly running) can never overwrite each other's increments. A read-modify-write in application code is a classic lost-update race condition.

**Q: How would you make the rate limiter's fixed window "smoother"?**
A fixed window (`INCR`+`EXPIRE`) allows a burst of up to `2 × max-requests` right at a window boundary (max requests at the very end of one window, plus max requests at the very start of the next). Common production alternatives: sliding-window log (`ZSET` of timestamps, trim old entries), sliding-window counter (weighted average of current + previous window), or token-bucket (steadier, allows small bursts by design). This project intentionally keeps the fixed-window version because it's the simplest way to teach `INCR`+`EXPIRE`.

**Q: What's the danger of caching a URL that later gets deleted?**
Handled here: `deleteShortUrl` explicitly calls `clickTrackingService.evictAll(shortCode)`, which deletes the cache entry (and pending counter, dirty-set membership, leaderboard entry) synchronously as part of the same delete request — so there's no window where a deleted short code still resolves from a stale cache entry.

**Q: Redis is down. What breaks?**
Everything that touches Redis: cache-aside reads (`resolveAndRecordClick` calls `redisTemplate.opsForValue().get(...)` with no fallback/circuit breaker), ID generation for new short URLs, click recording, rate limiting, and the leaderboard. This is a deliberate simplification for a teaching project — a production system would likely wrap Redis calls with a circuit breaker/timeout fallback (e.g., Resilience4j) so a Redis outage degrades gracefully (serve stale-if-error, or fall back straight to MySQL for reads) rather than failing every request.

**Q: How would you scale this horizontally?**
The service itself is stateless (all shared state lives in Redis/MySQL), so running N instances behind a load balancer works out of the box — the Redis `INCR` sequence and rate limiter are already correct under concurrency because Redis commands are single-threaded/atomic. The one thing to watch: the `@Scheduled` flush job would run on *every* instance independently; since `flushPendingClicks` drains each code's pending counter via `GETDEL` (only one instance's run gets a non-null value for a given code), this happens to be safe, but it's worth calling out explicitly as a subtle correctness property rather than an accident.

**Q: Common mistakes this codebase deliberately avoids (call these out if you see them in someone else's code):**
- Using `KEYS` in production code (this project uses a dirty set instead).
- Read-modify-write counters instead of atomic `INCR`/`UPDATE ... SET x = x + ?`.
- Caching with no TTL (memory leak risk) or a TTL longer than the underlying record's own validity (`cacheUrl` here caps the TTL at the mapping's `expiresAt`).
- Forgetting to evict the cache on delete (stale-data bug).
- Building rate-limit keys without a TTL, causing the counter to live forever instead of resetting per window.

---

## License

MIT — see [LICENSE](./LICENSE).
