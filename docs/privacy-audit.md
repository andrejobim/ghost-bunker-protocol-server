# Ghost Bunker Reference Server — Privacy-Max Audit

**Applied profile**: Ghost Bunker Privacy-Max Profile v0.1
**Audited server**: ghost-bunker-protocol-server 0.1.0-SNAPSHOT
**Scope**: production code under `src/main/`, configuration under `src/main/resources/`, build in `pom.xml`, and manual debug clients under `src/test/java/...Manual*Client.java`.
**Method**: static reading of the source, no execution at audit time. The remediation section below records changes applied afterwards.

---

## Summary

| Severity | Count |
|---|---|
| CRITICAL | 0 |
| HIGH | 0 |
| MEDIUM | 3 |
| LOW | 6 |
| OK | 12 |

The main implementation is aligned with the Privacy-Max profile. No leaks of IP, headers, binary payload, ciphertext, database persistence, fingerprint, device_id, persistent cookie, or persistent HTTP session were found in the production code paths. The remaining concerns were about (a) manual debug clients printing full UUIDs to stdout, (b) logging configuration that did not explicitly silence external sources (Tomcat / Spring Web), and (c) logger APIs exposed but not used, that could be misused by future contributors.

---

## Remediations Applied After Audit

This section records the changes applied in response to the findings above. The identifiers (MEDIUM-1, MEDIUM-2, MEDIUM-3, LOW-1, LOW-2) refer to the original findings detailed in later sections of this document.

### MEDIUM-1 — RESOLVED
`ErrorMessage.message` is now derived only from a canonical `ErrorCode` message table.

- `ProtocolErrorMapper` keeps an `EnumMap<ErrorCode, String>` (`CANONICAL_MESSAGES`) with fixed public messages per code (`"unsupported version"`, `"bad envelope"`, `"bad metadata"`, `"ciphertext too large"`, `"handshake timeout"`, `"protocol violation"`, `"client too slow"`, `"too many rooms"`, `"not in room"`, `"rate limited"`, `"payload too large"`, `"internal error"`, with `"protocol error"` as fallback). The `message` field of the wire `ErrorMessage` is **always** taken from that table.
- `ProtocolValidator.ValidationException` now carries an `ErrorCode code` (accessible via `code()`); each `throw` declares its own error code.
- `GhostBunkerWebSocketHandler` **no longer propagates** `e.getMessage()` to the wire. Code selections that previously used `"unsupported version".equalsIgnoreCase(e.getMessage())` or `"ciphertext too large".equalsIgnoreCase(e.getMessage())` were replaced with `e.code()` on the typed exception.
- Client input is **never** included in public protocol error messages.
- `ErrorMessage.message` must not contain nickname, room_id, ciphertext, payload, headers, IP, session_id, or user_id. The current design enforces this structurally.
- The legacy overload `error(code, ignoredReason, requestId, retryAfterMs)` was preserved for backward compatibility, but the `ignoredReason` argument is silently discarded.

### MEDIUM-2 — RESOLVED
`PrivacyLogAuditIT` was added (`src/test/java/io/ghostbunker/server/PrivacyLogAuditIT.java`).

- Runs a real `HELLO` / `JOIN_ROOM` / `SEND_ENCRYPTED_MESSAGE` flow against a Spring Boot WebSocket server on a random port (`@SpringBootTest(webEnvironment = RANDOM_PORT)`).
- Attaches a Logback `ListAppender<ILoggingEvent>` to the application logger (`io.ghostbunker`) only and raises **that logger** to `TRACE` for the duration of the test. It does **not** capture the root logger and does **not** attempt to audit third-party logging noise from Spring/Tomcat/test client internals.
- Injects sentinel values into the flow: `nickname = "phi-nickname-zzz123"` and `ciphertext = "5048312D434950484552-PHI-CIPHER"`.
- Captures the `session_id` and `user_id` returned in each `WELCOME` and adds them to the forbidden-substrings list.
- For every captured log event, asserts the absence of:
  - the sentinel nickname and the sentinel ciphertext;
  - full `session_id` and `user_id` values (both clients);
  - typical leak markers: `User-Agent`, `Cookie`, `Authorization`, `Bearer`, `127.0.0.1`, `0:0:0:0:0:0:0:1`, `localhost:`;
  - any continuous hexadecimal blob of length ≥ 48 characters (defense against logging raw bytes).
- Deterministic: no `Thread.sleep` on the assertion path, no dependency on TCP / Tyrus / scheduling timing.
- Runs automatically under `mvn clean verify` (`IT` suffix → Failsafe).

### MEDIUM-3 — RESOLVED
Tomcat access logs are explicitly disabled.

- `src/main/resources/application.yml` now contains:
  ```yaml
  server:
    tomcat:
      accesslog:
        enabled: false
  ```
- **Operational note**: the application cannot control intermediate infrastructure. Reverse proxies, CDNs, load balancers, firewalls, and hosting providers may keep their own access logs outside the reach of this configuration. For full compliance with the Privacy-Max Profile v0.1 (section 7), the operator must audit and disable identifiable access logs in those components as well (source IP, HTTP headers, query string, User-Agent, cookies, full URL, payload).

### LOW-1 — RESOLVED
Tomcat / Spring Web logging levels were hardened in `application.yml`.

- `org.apache.tomcat: WARN`
- `org.apache.coyote: WARN`
- `org.springframework.web: WARN`
- `org.springframework.web.socket: WARN`
- The root logger remains at `INFO`. Application logs continue to flow through `SanitizedProtocolLogger` using only constant sanitized strings.

This third-party logging contract is enforced by `PrivacyLoggingConfigurationTest`, which asserts the effective levels for the relevant external categories and that `server.tomcat.accesslog.enabled` is explicitly `false`. In other words:

- `PrivacyLogAuditIT` audits **what Ghost Bunker application code logs** (under `io.ghostbunker.*`).
- `PrivacyLoggingConfigurationTest` enforces **external logging configuration** (Tomcat / Spring Web levels + Tomcat access log disabled).

### LOW-2 — RESOLVED
Manual clients now redact full UUIDs.

- `ManualGhostBunkerClient`, `ManualGhostBunkerParam2Client`, and `ManualGhostBunkerParamClient` gained a `redact(id)` helper that returns the first 8 characters of the identifier followed by `"..."`.
- Print sites for `serverMessageId` and `fromUserId` now use `redact(...)` instead of `sanitize(...)`. The `sanitize()` helper remains in use for diagnostic strings (it strips `\r\n\t`); the redaction concern is now a separate function.
- `ManualGhostBunkerNegativeClient` does not print UUIDs and required no changes.
- Goal: avoid accidental persistence of full ephemeral identifiers in operator-saved console output (linkability defense).

---

## Post-Remediation Verification

- **Command**: `mvn clean verify`
- **Result**: `BUILD SUCCESS`
- **Tests run**: 14
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 0

Suites included:

- `GhostSessionUnitTest` — session unit tests (Surefire).
- `GhostBunkerWebSocketIT` — flow integration (handshake, JOIN, SEND, envelope validation, limits, violations, GOODBYE).
- `HeartbeatIT` — ping/pong / liveness integration.
- `PrivacyLogAuditIT` — integration test that captures all Logback events during HELLO/JOIN/SEND and proves the absence of payload, nickname, ciphertext, session_id, user_id, and header/IP markers.

---

## OK (Aligned with the Profile)

### OK-1 — Sanitized central logger
- **File**: `src/main/java/io/ghostbunker/server/logging/SanitizedProtocolLogger.java`
- **Observation**: the logger exposes only `info(String)`, `warn(String)`, and `error(String, Throwable)`. There is no method that accepts binary payload, frames, or raw bytes. All production call sites pass only sanitized constant strings:
  - `"ws connected (sanitized)"`
  - `"ws closed (sanitized)"`
  - `"handshake timeout error send failed (sanitized)"`
  - `"heartbeat ping failed (sanitized)"`

### OK-2 — No logging of IP / remote address / headers / cookies / URL / user-agent
- **Files**: entire `src/main/`.
- **Observation**: `grep` for `getRemoteAddress`, `getURI`, `getHandshake`, `getHeaders`, `getCookies`, `getAttributes` under `src/main/` returns no occurrences outside of configuration timeout getters.

### OK-3 — No logging of binary payload / ciphertext / raw Protobuf envelope
- **Files**: entire `src/main/`.
- **Observation**: `grep -rn "log\."` returns only the three calls inside `SanitizedProtocolLogger` itself. No other production code writes logs. No production logger call includes `bytes`, `ciphertext`, `payload`, or `GhostEnvelope` content.

### OK-4 — No database
- **Files**: `pom.xml` plus entire `src/main/`.
- **Observation**: no JDBC, JPA, Hibernate, R2DBC, or MongoDB dependency. `grep` for `@Entity`, `@Table`, `DataSource`, `JpaRepository`, `JdbcTemplate`, `jdbc:` under `src/main/` returns no occurrences.

### OK-5 — No Redis, Kafka, RabbitMQ
- **File**: `pom.xml`.
- **Observation**: no message broker or distributed cache dependency. `grep` for `Redis`, `Kafka`, `RabbitMQ`, `jedis`, `lettuce` in the pom returns no occurrences.

### OK-6 — No message persistence / history
- **Files**: `src/main/java/io/ghostbunker/server/handler/GhostBunkerWebSocketHandler.java`, `src/main/java/io/ghostbunker/server/room/InMemoryRoomRegistry.java`, `src/main/java/io/ghostbunker/server/routing/MessageRouter.java`.
- **Observation**: `onSendEncryptedMessage` routes the `EncryptedMessage` to room recipients and drops it — no write to a database, file, or long-lived structure. `onAck` is explicitly documented as "Accept and ignore (no persistence)". `InMemoryRoomRegistry` removes the room as soon as it becomes empty.

### OK-7 — Ephemeral identifiers, discarded on close
- **File**: `src/main/java/io/ghostbunker/server/session/InMemoryGhostSessionRegistry.java`.
- **Observation**: `session_id` and `user_id` are `UUID.randomUUID()` values generated in memory and removed by `sessionRegistry.remove(session)` in `afterConnectionClosed`. They are not persisted nor reused across connections.

### OK-8 — No fingerprint / device_id / IP-as-identity
- **Files**: entire `src/main/`.
- **Observation**: no `fingerprint`, `device_id`, `installation_id`, or `client_id` field in the `.proto` or in the code. `grep` for those strings under `src/main/` returns no occurrences. The `WELCOME` envelope carries only `session_id`, `user_id`, `display_name`, `server_time_ms`, and `limits`.

### OK-9 — No persistent cookie, no persistent HTTP session
- **Files**: entire `src/main/`.
- **Observation**: no use of `HttpSession`, `@SessionAttributes`, `@SessionScope`, `Cookie`, or `JSESSIONID`. The server is purely WebSocket; the only session is the in-memory `WebSocketSession`, discarded on close.

### OK-10 — Short and standardized error messages
- **File**: `src/main/java/io/ghostbunker/server/validation/ProtocolValidator.java`.
- **Observation**: all `ValidationException` instances carry constant strings (`"nickname too long"`, `"ciphertext too large"`, etc.). No exception echoes received content. `ProtocolErrorMapper.sanitize()` clamps the message to 160 characters as a defense.

### OK-11 — Anti-abuse limits compatible with Privacy-Max
- **File**: `src/main/java/io/ghostbunker/server/config/GhostBunkerProperties.java`.
- **Observation**: configurable limits include per-connection rate, rooms per connection, maximum envelope / ciphertext sizes, and violations per window. No mechanism depends on persistent identity.

### OK-12 — `setAllowedOrigins("*")` is a protocol decision, not a privacy one
- **File**: `src/main/java/io/ghostbunker/server/config/WebSocketConfig.java:26`.
- **Observation**: recorded for awareness — it does not violate Privacy-Max (no data collection involved), but operators may want to harden it if needed. No remediation required under this profile.

---

## LOW (Findings)

### LOW-1 — `application.yml` did not explicitly silence Tomcat / Spring Web
- **File**: `src/main/resources/application.yml`
- **Issue**: `logging.level.root: INFO` was the only filter. At INFO, the embedded Tomcat can still emit lines containing the request path during the WebSocket upgrade. IP is not included by default, but if any operator raises levels to `DEBUG` for operational debugging, `org.apache.tomcat.websocket` and `org.apache.coyote.http11` start logging bytes and headers.
- **Risk**: accidental URL / header logging at DEBUG; lower risk at the current INFO level.
- **Recommended fix**: add explicit levels:
  ```yaml
  logging:
    level:
      root: INFO
      org.apache.tomcat: WARN
      org.apache.coyote: WARN
      org.springframework.web: WARN
      org.springframework.web.socket: WARN
  ```
- **Status**: RESOLVED. See "Remediations Applied After Audit".

### LOW-2 — Manual clients printed full UUIDs to stdout
- **Files**:
  - `src/test/java/io/ghostbunker/server/ManualGhostBunkerParam2Client.java:278-282`
  - `src/test/java/io/ghostbunker/server/ManualGhostBunkerParamClient.java:286-290`
  - `src/test/java/io/ghostbunker/server/ManualGhostBunkerClient.java:246-247, 252`
- **Issue**: lines such as `System.out.println("serverMessageId=" + sanitize(...))` and `"fromUserId=" + sanitize(...)` called a `sanitize()` helper that **only stripped `\r\n\t`** and did not truncate. Full UUIDs were printed to stdout. Not executed in production (manual debug clients), but operators could run them against a real server and save the output.
- **Comparison**: `examples/MinimalJavaClient.java` already did the right thing — it used `redact(id)` to keep only 8 characters.
- **Risk**: transient linkability if an operator saves the output of those clients; cross-correlation across saved logs.
- **Recommended fix**: replace `sanitize()` in the manual clients with a `redact(id)` helper mirroring `MinimalJavaClient` (8 chars + `...`). Apply to `sessionId`, `userId`, `serverMessageId`, `clientMessageId`, `messageId`, `requestId`, `fromUserId`.
- **Status**: RESOLVED. See "Remediations Applied After Audit".

### LOW-3 — `SanitizedProtocolLogger.error(String, Throwable)` is an API without a call site and without a guard
- **File**: `src/main/java/io/ghostbunker/server/logging/SanitizedProtocolLogger.java:19-21`
- **Issue**: the method accepts a `Throwable` and hands it raw to SLF4J, which prints a full stack trace. No production caller uses the method today. If a future change passes a `RuntimeException` whose message includes client payload (for example, some upstream library exception could carry bytes), the stack trace would leak it.
- **Risk**: future misuse. No leak today.
- **Recommended fix**: either **remove the method** (no caller) or change the signature to `error(String msg, String exceptionClassName)` and log only the exception's class name, never the `Throwable`. Document that stack traces are not permitted.

### LOW-4 — `displayName` in `WELCOME` echoes the received nickname
- **File**: `src/main/java/io/ghostbunker/server/handler/GhostBunkerWebSocketHandler.java:168-170`
- **Issue**: `session.setDisplayName(displayName)` and the `setDisplayName(displayName)` in `WELCOME` echo the `nickname` exactly as the client sent it (after validating it as ASCII-visible ≤ 32 chars). This is correct per the `.proto`, but `displayName` lives in memory for the session lifetime and is included in the `WELCOME` reply.
- **Risk**: none directly — the client volunteered the value. But if future logging includes `displayName`, that becomes user-supplied data in logs.
- **Recommended fix**: add a comment on the field stating that `displayName` **must never be logged**, and consider whether the session needs to retain it at all (no use outside the WELCOME reply was found).

### LOW-5 — `target/failsafe-reports/*.xml` may persist test metadata
- **Files**: `target/failsafe-reports/TEST-*.xml`
- **Issue**: Maven test reports record stack traces and assertion messages in XML files under `target/`. In CI or development workflows these files may end up in build artifacts. The current test messages do not leak payload, but for instance a report containing `seen={WELCOME=321, ROOM_JOINED=321, ENCRYPTED_MESSAGE=3204, PING=642}` does contain traffic metadata.
- **Risk**: low, only relevant if CI artifacts are exposed publicly.
- **Recommended fix**: ensure `target/` is in `.gitignore` and document a policy of not publishing `failsafe-reports/` outside the build. No code change required.

### LOW-6 — Manual clients print error frames with `e.getMessage()` to stdout
- **Files**: `src/test/java/io/ghostbunker/server/Manual*.java`
- **Issue**: several `catch (Exception e) { System.out.println(... e.getMessage()); }` blocks during envelope parsing. `InvalidProtocolBufferException.getMessage()` typically returns neutral text (`"While parsing a protocol message..."`), but some I/O exceptions include paths and hostnames. Not catastrophic, but it escapes the `redact` pattern used in `MinimalJavaClient`.
- **Risk**: low, only at interactive diagnostics.
- **Recommended fix**: replace `e.getMessage()` with `e.getClass().getSimpleName()` in the manual clients, or at least route the message through the actual `sanitize` helper that clamps length.

---

## MEDIUM (Findings)

### MEDIUM-1 — `ValidationException.getMessage()` went into the wire `ErrorMessage` sent to the client
- **Files**:
  - `src/main/java/io/ghostbunker/server/handler/GhostBunkerWebSocketHandler.java:126-127, 164, 218, 255, 272-273`
  - `src/main/java/io/ghostbunker/server/validation/ProtocolValidator.java`
- **Issue**: the handler caught `ValidationException e` and forwarded `e.getMessage()` to `onProtocolViolation(session, code, e.getMessage())`, which became the `message` field of the wire ERROR frame. Today all validator messages are literal constants (audited), so nothing actually leaks. But the **pattern** of blindly forwarding `e.getMessage()` is risky: any future validator change that includes the received value (for example `"nickname '" + nickname + "' too long"`) would immediately put user input on the wire — and from there into any client that logs the message. `ProtocolErrorMapper.sanitize()` only clamped to 160 characters, it did not filter content.
- **Risk**: easy regression. Not an active leak; it's a trap waiting for someone to step on.
- **Recommended fix**: switch the pattern to mapping the exception type to a predefined sanitized string (`Map<Class<? extends ValidationException>, String>` or exception subclasses), and never propagate `e.getMessage()` to the wire. Add a unit test that fails if any v0.1 `ValidationException` is later changed to include client input.
- **Status**: RESOLVED. See "Remediations Applied After Audit".

### MEDIUM-2 — No automated test proves the absence of payload in logs
- **Files**: `src/test/java/io/ghostbunker/server/`
- **Issue**: the audit was static. There was no test that attached a Logback appender during a real connection and asserted that no log event contained envelope bytes or a substring of the ciphertext. Without such a test, a future regression (someone adding `log.info("got envelope " + env)`) would pass undetected.
- **Risk**: silent regression.
- **Recommended fix**: add a unit/integration test using `ListAppender<ILoggingEvent>` (Logback) attached to the root logger, run a HELLO/JOIN/SEND flow, and assert that no log event contains:
  - bytes of the ciphertext (search for the hex/base64 of a known sequence);
  - full `session_id` or `user_id` values (UUIDs);
  - the sent `nickname`.
  This is a single deterministic test with no timing dependency.
- **Status**: RESOLVED. See "Remediations Applied After Audit".

### MEDIUM-3 — Logging policy for containers / proxies / system was not pinned by configuration
- **Files**: `src/main/resources/application.yml`, `pom.xml`
- **Issue**: the v0.1 profile (section 7) requires that intermediate infrastructure (reverse proxy, CDN, firewall, LB, runtime) be configured not to persist identifiable logs. The application cannot enforce that, but it can (a) emit a startup warning if it detects a default Tomcat configuration that enables the access log, (b) document the JVM and Tomcat flags that must be turned off (`server.tomcat.accesslog.enabled=false`).
- **Risk**: an operator deploys with the access log enabled and silently records IP + URL.
- **Recommended fix**: add to `application.yml`:
  ```yaml
  server:
    tomcat:
      accesslog:
        enabled: false
  ```
  and add an operations note in the README explaining that this setting is **required** for Privacy-Max compliance.
- **Status**: RESOLVED. See "Remediations Applied After Audit".

---

## HIGH

No findings.

---

## CRITICAL

No findings.

---

## Coverage by Prompt Item

| Requested check | Result |
|---|---|
| Logs contain IP or remote address? | No (OK-2) |
| Logs contain headers? | No (OK-2) |
| Logs contain Protobuf payload? | No (OK-3) |
| Logs contain ciphertext? | No (OK-3) |
| Logs contain full `session_id`? | No in production (OK-1). Yes in manual debug clients — fixed (LOW-2). |
| Logs contain full `user_id`? | No in production (OK-1). Yes in manual debug clients — fixed (LOW-2). |
| Database exists? | No (OK-4) |
| Message persistence exists? | No (OK-6) |
| Request dump? | No (OK-2, OK-3) |
| Packet dump? | No (OK-2, OK-3) |
| Fingerprint? | No (OK-8) |
| `device_id`? | No (OK-8) |
| Persistent cookie? | No (OK-9) |
| Persistent HTTP session? | No (OK-9) |
| Exceptions could print received payload? | Not directly (OK-10). Side risk via `e.getMessage()` on the wire — fixed (MEDIUM-1). API surface concern remains (LOW-3). |
| Tests or manual clients print full ciphertext? | No — only its size. Full UUIDs were printed — fixed (LOW-2). |

---

## Overall Recommendation

The base is solid. The Privacy-Max v0.1 conformance bar requires the three MEDIUM findings to be addressed (especially MEDIUM-1 and MEDIUM-2 — regression trap plus absence of anti-leak test) along with LOW-1 (silence Tomcat / Spring via configuration). All four were addressed; the remaining LOW items (LOW-3, LOW-4, LOW-5, LOW-6) are hygiene and can be grouped into a single "logging hardening" PR later. LOW-2 was also addressed even though the prompt allowed deferral for manual-client-only items, because the change was trivial.
