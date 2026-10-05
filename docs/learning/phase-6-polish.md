# Phase 6: Polish (learning notes)

Phase 6 added four production features around the core booking engine: a Redis cache for the public catalog, Redis token-bucket rate limiting, pre-signed image uploads to Cloudflare R2, and generated OpenAPI docs. The common theme is **using Redis for more than holds, without making Redis a single point of failure**, and **keeping heavy or risky work out of the app server**. Code is under `backend/src/main/java/org/saket/eventbooking/`.

## 1. Request walkthroughs

### A. A visitor opens an event page (cache hit and live availability)

`GET /api/v1/events/{id}`:

1. `event/controller/EventController.get` makes two calls:
   - `eventService.getPublishedDetail(id)`. The Spring proxy sees `@Cacheable(EVENT_DETAIL, key = "#id.toString()")` and runs `GET event-detail::<id>` on Redis. **Hit:** the JSON is turned back into an `EventDetailResponse` by that cache's typed serializer and the method body never runs. **Miss:** the method runs (`findById`, line-up, images), and the result is stored with a 10-minute TTL.
   - `sessionService.listUpcomingForPublishedEvent(id)`. **Not cached**: it reads sessions with `ticketsAvailable` straight from Postgres.
2. The controller merges both into `PublicEventResponse`. So the event text can be up to 10 minutes old (or until an admin edit evicts it), but the "tickets left" number is always current.

### B. An admin renames the event (eviction after commit)

`PUT /api/v1/admin/events/{id}` → `EventService.update`, which carries:
- `@EvictsEventListings`, our meta-annotation for `@CacheEvict(cacheNames = {event-list, event-categories}, allEntries = true)`;
- `@CacheEvict(EVENT_DETAIL, key = "#id.toString()")`.

The cache manager in `common/cache/CacheConfig` is `transactionAware()`, so the evictions are registered as "after commit" callbacks. The update commits, then `DEL event-detail::<id>` runs, and a SCAN + DEL clears every `event-list::*` page. The next reader misses and loads the new title.

### C. Someone brute-forces a login

`POST /api/v1/auth/login` → `auth/controller/AuthController.login`:

1. `common/ratelimit/RateLimits.login(request, email)` calls `TokenBucketRateLimiter.consume` twice: bucket `login-ip` keyed by `request.getRemoteAddr()`, then bucket `login-email` keyed by the lower-cased email.
2. Each `consume` runs `resources/redis/token-bucket.lua` with one `EVALSHA`: read `tokens` and `ts` from the hash, add tokens for the elapsed time (Redis `TIME`), take one or compute how long until one is available, write back, `PEXPIRE`.
3. If the script says no, `RateLimitExceededException` is thrown. `GlobalExceptionHandler.handleRateLimited` answers **429** with `Retry-After: <seconds>` and the usual `ApiError` body. Only if both buckets allow it does `AuthService.login` run.

### D. An admin uploads an event poster

1. `GET /api/v1/admin/uploads/images` → `media/service/ImageUploadService.options()`: `{directUpload, allowedContentTypes, maxBytes}`. If `directUpload` is false (no R2 credentials), the UI shows an image-URL field instead and stops here.
2. `POST /api/v1/admin/uploads/images` with `{purpose: "EVENT", contentType: "image/png", contentLength: 482113}` → `ImageUploadService.presign` checks the type against `image/jpeg|png|webp` and the size against 5MB, builds the key `events/2026/10/<uuid>.png`, and calls `common/storage/R2ObjectStorage.presignUpload`.
3. `R2ObjectStorage` uses the AWS SDK's `S3Presigner` to sign a `PUT` locally (no network). Content-Type and Content-Length are in `X-Amz-SignedHeaders`, so R2 rejects any other type or size.
4. The browser `PUT`s the file to `uploadUrl`, then saves the returned `imageUrl` with the normal `POST /admin/events/{id}/images`.

## 2. Key classes

| Class | Role | Without it |
|---|---|---|
| `common/cache/CacheConfig` | Builds the `RedisCacheManager`: typed serializer per cache, TTLs, transaction-aware, immediate writes, SCAN clearing, a log-and-continue `CacheErrorHandler` | `@Cacheable` would have no backing store; a Redis blip would 500 the catalog |
| `common/cache/CacheNames`, `EvictsEventListings` | Cache names in one place; one annotation for "this write changes listings" | Easy to forget one of the two listing caches on a new admin write |
| `event/service/EventService` (`@Cacheable` methods) | The three cached reads: `searchPublished`, `listPublishedCategories`, `getPublishedDetail` | Every public request hits Postgres |
| `common/ratelimit/TokenBucketRateLimiter` + `redis/token-bucket.lua` | Atomic bucket check-and-take in Redis, hashed keys, fails open | Concurrent requests could both take the last token; or limits per instance only |
| `common/ratelimit/RateLimits` | Which buckets apply to which endpoint (IP + email, per user) | Limits scattered across controllers |
| `common/storage/ObjectStorage`, `R2ObjectStorage`, `UnconfiguredObjectStorage`, `StorageConfig` | Storage behind an interface, with a no-credentials fallback | App wouldn't run locally without R2 keys |
| `media/service/ImageUploadService` | Validates type/size, picks the key, returns the pre-signed upload | Clients could choose keys (overwrite objects) or upload anything |
| `common/config/OpenApiConfig` | API title/how-to and the bearer-JWT scheme for Swagger UI | Swagger UI couldn't call protected endpoints |

## 3. Concepts used

- **Cache-aside** (`@Cacheable`): read cache → on miss read DB and fill the cache. Writes go to the DB and *evict*, never update, the cache. See `EventService`.
- **Spring cache proxies**: like `@Transactional`, `@Cacheable` only works on calls that go through the bean proxy (from another bean), not `this.method()`.
- **Transaction-aware cache**: `RedisCacheManager.builder(...).transactionAware()` wraps caches in `TransactionAwareCacheDecorator`, which defers put/evict until `afterCommit`. Evicting *before* commit lets a reader re-cache the old row.
- **Async cache writes on Lettuce**: Spring Data Redis clears asynchronously by default on Lettuce; `RedisCacheWriter.create(cf, c -> c.immediateWrites())` makes it synchronous. `EventCacheTest` failed until this was set.
- **Typed serializers**: `JacksonJsonRedisSerializer` with a `JavaType` such as `PageResponse<EventSummaryResponse>`. No `@class` in the JSON, so no polymorphic-deserialization risk.
- **Token bucket**: capacity = burst; tokens refill continuously at `capacity / refillPeriod`. Smoother than a fixed window, which allows 2x bursts at window edges.
- **Lua scripts in Redis**: a script runs atomically (no other command interleaves), which makes read-modify-write safe across many app instances. `RedisScript.of(resource, List.class)`; Spring sends `EVALSHA` and falls back to `EVAL`.
- **Fail open vs. fail closed**: the limiter and the cache both allow the request if Redis errors. Availability wins over protection for a short outage.
- **Client IP behind proxies**: trust `X-Forwarded-For` only from known proxies (Tomcat `RemoteIpValve` via `server.forward-headers-strategy: native`, planned for Phase 7); `getRemoteAddr()` otherwise.
- **Pre-signed URLs (SigV4)**: an HMAC signature over method, path, signed headers and expiry, created with the secret key. Whoever holds the URL can do exactly that one request until it expires.
- **springdoc**: scans `@RestController`s at runtime to generate OpenAPI 3; Swagger UI is served as a webjar. Permitted in `SecurityConfig`, turned off by `API_DOCS_ENABLED=false`.

## 4. Try it yourself

1. **See the eviction race fixed by immediate writes.** In `CacheConfig`, delete the `.immediateWrites()` line and run:
   ```
   ./mvnw test -Dtest=EventCacheTest
   ```
   `listingIsCachedAndEvictedWhenSessionsChange` and the categories test fail: the admin write returned before Redis was cleared. Put the line back.
2. **Prove the bucket is atomic.** In `token-bucket.lua`, temporarily split the read and write by moving the logic to Java (or, quicker: change `TokenBucketRateLimiterTest.concurrentRequestsNeverOverdrawTheBucket` to call `consume` from 50 threads with capacity 5) and run:
   ```
   ./mvnw test -Dtest=TokenBucketRateLimiterTest
   ```
   With the script it is always exactly 5. A Java GET-then-SET version lets several threads read "1 token left" together.
3. **Watch caching and limits on the running app.** `docker compose up -d`, `./mvnw spring-boot:run`, then:
   ```
   curl -s localhost:8080/api/v1/events/<id> > /dev/null
   docker exec backend-redis-1 redis-cli --scan --pattern 'event-*'
   for i in $(seq 1 12); do curl -s -o /dev/null -w "%{http_code} " -H 'Content-Type: application/json' -d '{"email":"x@example.test","password":"x"}' localhost:8080/api/v1/auth/login; done
   ```
   You'll see the `event-detail::<id>` key, then ten 401s followed by 429s. Open `http://localhost:8080/swagger-ui.html` and try the same login there.

## 5. Interview questions

1. **Why not cache the whole event page, sessions included?** Sessions carry `ticketsAvailable`, which changes on every booking. A cached "3 left" can sell seats that don't exist in the user's mind and causes failed checkouts. The response is split: event, line-up and images (change only on admin edits) are cached; sessions are read live and merged in the controller.
2. **How do you keep the cache consistent with the database?** Cache-aside with eviction on every admin write, after commit (transaction-aware manager), plus TTLs as a backstop. A small race remains: a reader that loaded old data just before the commit can write it to the cache just after the eviction. The TTL bounds that staleness (60s for listings, 10m for detail). Fixing it fully needs versioned keys or write-through, which isn't worth it for catalog text.
3. **Why clear all listing pages instead of evicting precisely?** Listing keys are built from filters (city, category, dates, page), so one event appears under unknown keys. Clearing with SCAN is cheap at this size, and admin writes are rare compared to reads. Free-text searches aren't cached at all, because their key space is unbounded.
4. **Why a Lua script for rate limiting?** The check and the decrement must be atomic across all app instances. A script runs without interleaving, uses Redis's clock (no skew between servers), and costs one round trip. Alternatives: Bucket4j (another dependency), or `INCR` + `EXPIRE` fixed windows (simpler, burstier at window edges).
5. **Why limit login per IP *and* per email?** Per IP stops one machine trying many accounts (credential stuffing). Per email stops many machines targeting one account. The trade-off: an attacker can briefly lock a victim out of login by burning their email bucket; that's why it refills in 15 minutes rather than locking the account.
6. **What happens if Redis goes down?** Holds can't be created (that was already a hard dependency), but catalog reads fall back to Postgres (`CacheErrorHandler` logs and treats it as a miss) and rate limits fail open (log and allow). Protection degrades instead of taking the site down.
7. **Why pre-signed URLs instead of uploading through the backend?** The app never streams file bytes, so uploads don't hold threads or memory. The server keeps control: it validates type and size, chooses the object key (no overwrites, no path tricks), signs Content-Type and Content-Length so the bucket enforces them, and the URL expires in 10 minutes. The cost is a two-step client flow.
8. **How does the app run without R2 credentials?** `StorageConfig` picks `UnconfiguredObjectStorage` when any R2 setting is blank. The options endpoint reports `directUpload: false`, presigning answers 501, and admins paste an image URL (validated to be http(s)). The same interface-plus-fallback pattern is used for email and payments.
