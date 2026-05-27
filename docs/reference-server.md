# Reference Server Architecture

This document describes the actual architecture of the Ghost Bunker reference server
as it exists in this repository. It is a description of code, not an idealized
design.

---

## 1. Stack

- **Language**: Java 21.
- **Framework**: Spring Boot 3.5.0, using `spring-boot-starter-websocket`.
- **Embedded server**: the default Spring Boot servlet container (Tomcat with WebSocket
  support).
- **Wire format**: Protobuf 3 (`com.google.protobuf:protobuf-java:3.25.6`).
- **Build**: Apache Maven. `protobuf-maven-plugin` 0.6.1 generates Java sources from
  `src/main/proto/ghost_bunker_v1.proto`; `os-maven-plugin` 1.7.1 selects the right
  `protoc` binary for the host. Surefire runs unit tests; Failsafe runs integration
  tests (`*IT.java`).
- **Test libraries**: `spring-boot-starter-test`, AssertJ, Mockito.

There is no database driver, no message broker, no cache, no service registry, no
configuration server, and no metrics or tracing exporter.

---

## 2. Top-level packages

All source lives under `io.ghostbunker.server.*`. Generated Protobuf classes live
under `io.ghostbunker.protocol.v1.*`.

| Package | What lives there |
|---|---|
| `io.ghostbunker.server` | The Spring Boot main class. |
| `io.ghostbunker.server.config` | Configuration properties and WebSocket registration. |
| `io.ghostbunker.server.handler` | The WebSocket handler that drives the protocol. |
| `io.ghostbunker.server.protocol` | Envelope decoding/encoding and the effective limits view. |
| `io.ghostbunker.server.session` | Per-connection session state and registry. |
| `io.ghostbunker.server.room` | In-memory rooms and the room registry. |
| `io.ghostbunker.server.routing` | Room-scoped recipient selection. |
| `io.ghostbunker.server.validation` | Envelope and payload validation. |
| `io.ghostbunker.server.rate` | Per-connection rate limiter. |
| `io.ghostbunker.server.backpressure` | Outbound-queue back-pressure policy. |
| `io.ghostbunker.server.heartbeat` | Server-driven heartbeat, handshake timeout, graceful close. |
| `io.ghostbunker.server.error` | Error envelope builder. |
| `io.ghostbunker.server.logging` | Sanitized logger façade. |

---

## 3. Components by class

### 3.1 `GhostBunkerReferenceServerApplication`

The Spring Boot entry point. Annotated `@SpringBootApplication` and
`@EnableConfigurationProperties(GhostBunkerProperties.class)`. Nothing else lives in
this class.

### 3.2 `GhostBunkerProperties` (config)

`@ConfigurationProperties(prefix = "ghostbunker")`. The inner `Limits` class holds
every tunable: protocol version strings, envelope/ciphertext size caps,
nickname/room-id char caps, room cap, rate-limit windows, outbound-queue thresholds,
handshake/heartbeat/idle timeouts, violation budget, and `send-time-limit-ms`.
Defaults are documented in the README.

### 3.3 `ProtocolLimits` (protocol)

A `@Component` that wraps `GhostBunkerProperties.Limits` and exposes each field as a
typed accessor. Other components depend on `ProtocolLimits` rather than directly on
the properties bean.

### 3.4 `WebSocketConfig` (config)

`@Configuration @EnableWebSocket`. Implements `WebSocketConfigurer`. Registers
`GhostBunkerWebSocketHandler` at path `/ghost-bunker` with `setAllowedOrigins("*")`
(suitable for local dev only). Defines a `ServletServerContainerFactoryBean`
configured with `maxBinaryMessageBufferSize = max_envelope_bytes + 16 384` to ensure
slightly oversized frames are still delivered to the application layer so the server
can return a protocol-level `ERROR` of code `BAD_ENVELOPE` before closing. The
text-frame buffer is kept small (1 024 bytes) since text frames are unused.

### 3.5 `GhostEnvelopeDecoder` / `GhostEnvelopeEncoder` (protocol)

Thin wrappers around generated Protobuf parse/serialize. The decoder rejects null
payloads and payloads larger than `max_envelope_bytes` by throwing
`InvalidProtocolBufferException`. The encoder refuses to emit envelopes larger than
`max_envelope_bytes`. Both are constructed by `GhostBunkerWebSocketHandler` and
`HeartbeatService` directly, not as Spring beans.

### 3.6 `ProtocolValidator` (validation)

`@Component`. Centralizes all envelope and payload validation logic:

- `validateBaseEnvelope`: checks `protocol`, `version`, and non-unspecified `type`.
- `validateTypeMatchesPayload`: verifies the `oneof` branch matches the declared
  `type`.
- `validateNickname`: ASCII-visible (`^[\x21-\x7E]+$`), within
  `max_nickname_chars`.
- `validateRoomId`: required, ASCII-visible, within `max_room_id_chars`.
- `validateSendEncryptedMessage`: ciphertext non-empty and within
  `max_ciphertext_bytes`, nonce non-empty, `key_id` non-blank, `cipher_suite` not
  `UNSPECIFIED`.

All failures throw `ProtocolValidator.ValidationException`. The exception carries
the intended `ErrorCode` via `code()`. The handler catches the exception and uses
`e.code()` directly (it does not inspect `e.getMessage()` to choose the code, and it
does not “map message text to `ErrorCode`”).

### 3.7 `InMemoryGhostSessionRegistry` / `GhostSession` / `GhostSessionState` (session)

`InMemoryGhostSessionRegistry` is a `@Component` that owns a
`ConcurrentHashMap<String, GhostSession>` keyed by the WebSocket session id. It
generates random UUIDs for `sessionId` and `userId` on creation. Sessions are created
in `afterConnectionEstablished`, removed in `afterConnectionClosed`.

`GhostSession` encapsulates the per-connection state:

- The decorated `WebSocketSession` (a `ConcurrentWebSocketSessionDecorator` that
  enforces `send-time-limit-ms` and `max-outbound-pending-bytes`) and the raw
  underlying `WebSocketSession`. `GOODBYE` is written to the raw session so it
  bypasses the decorator's pending-bytes/send-time check.
- The current `GhostSessionState`.
- `displayName` (the nickname or synthesized `anon-…` value).
- The set of joined rooms (a `ConcurrentHashMap.newKeySet()`).
- Heartbeat timestamps: `lastActivityMs`, `lastPingSentAtMs`, `lastPongAtMs`.
- Sliding-window counters for `commandsInWindow` and `messagesInWindow`, each with
  its own `commandWindowStartMs` / `messageWindowStartMs` reset when the window
  rolls over (every 60 seconds).
- A deque of recent protocol-violation timestamps used to detect repeated
  violations.
- Outbound back-pressure counters: `outboundQueuedMessages` and
  `outboundPendingBytes`.
- A `slowClientFlagged` `AtomicBoolean` used to ensure `GOODBYE` is emitted at most
  once per session when slow-client back-pressure trips.

`GhostSessionState` defines: `CONNECTED`, `AWAITING_HELLO`, `ESTABLISHED`,
`IN_ROOMS`, `CLOSING`, `CLOSED`.

### 3.8 `InMemoryRoomRegistry` / `Room` (room)

`InMemoryRoomRegistry` is a `@Component` holding a `ConcurrentHashMap<String, Room>`.
Rooms are created on first join (`computeIfAbsent`) and removed when their participant
map becomes empty.

`Room` holds a `ConcurrentHashMap<String, WebSocketSession>` of participants keyed by
WebSocket session id, plus accessors for `onlineCount`, `add`, `removeById`, and
`isEmpty`.

### 3.9 `MessageRouter` (routing)

`@Component`. Single responsibility: given a `roomId` and a sender's
`WebSocketSession`, return the list of other open WebSocket sessions in that room.
Used by the handler when fanning out `ENCRYPTED_MESSAGE`. Also exposes
`routeScope(env) -> env.getRoomId()` for symmetry.

### 3.10 `PerConnectionRateLimiter` (rate)

`@Component`. A trivial façade that delegates to
`GhostSession.tryIncrementCommands()` / `tryIncrementMessages()`. The actual
sliding-window arithmetic lives on `GhostSession`. The handler asks
`allowCommand(session)` on every inbound frame and `allowMessage(session)` on
`SEND_ENCRYPTED_MESSAGE`.

### 3.11 `OutboundQueuePolicy` (backpressure)

Plain Java helper (not a Spring bean). Checks whether enqueuing a message of size N
on a given session would exceed `max_outbound_queue_messages` or
`max_outbound_pending_bytes`. Returns `false` when the message must be dropped (and
the recipient marked as a slow client).

### 3.12 `ProtocolErrorMapper` (error)

`@Component`. Builds a fully-formed `GhostEnvelope` of type `ERROR`.

- The public `ErrorMessage.message` is **canonicalized**: it is chosen from a
  fixed `ErrorCode → message` table owned by the mapper.
- Callers provide the `ErrorCode`, `requestId`, and `retryAfterMs`.
- The mapper does **not** use a caller-provided string as the public on-wire message.
  A legacy overload that accepts a `reason`/`ignoredReason` string exists only for
  backward compatibility and discards the string.

### 3.13 `SanitizedProtocolLogger` (logging)

`@Component` wrapping an SLF4J logger. Exposes `info(String)`, `warn(String)`, and
`error(String, Throwable)`. Callers are expected to pass only constant sanitized
strings — there are no formatter placeholders and no interpolation of client input.
This is the **only** logging entry point the application code uses for protocol
events.

### 3.14 `HeartbeatService` (heartbeat)

`@Component`. Owns a single-thread `ScheduledExecutorService` named
`ghostbunker-heartbeat`. For each new session it schedules:

- A fixed-rate `tick` task at `ping_interval_ms`. Each tick checks idle, checks
  pong-timeout, and sends a fresh `PING` if neither triggered.
- A one-shot handshake-timeout task at `handshake_timeout_ms`. If the session is
  still in `AWAITING_HELLO` when it fires, the service sends `ERROR(HANDSHAKE_TIMEOUT)`,
  then `GOODBYE(PROTOCOL_ERROR, "handshake timeout")`, then closes.

`closeWithGoodbye(session, reason, sanitizedMessage)` is the canonical close path. It
writes `GOODBYE` to the **raw** WebSocket session (synchronized on the raw session) to
bypass the concurrent-send decorator's back-pressure limits, then schedules a 50 ms
delayed close so the bytes have a chance to leave the kernel.

`GoodbyeReasonMessages.canonical` provides the recommended sanitized message string
per `DisconnectReason`. The current code allows callers to pass a custom message;
operators wanting strict Privacy-Max wire content should pass the canonical value
from this table.

### 3.15 `GhostBunkerWebSocketHandler` (handler)

The protocol's core. Extends `BinaryWebSocketHandler`. Wires together every component
above.

`afterConnectionEstablished`:

1. Wraps the raw `WebSocketSession` in a `ConcurrentWebSocketSessionDecorator` with
   `send-time-limit-ms` and `max-outbound-pending-bytes`.
2. Calls `sessionRegistry.create(decorated, raw)` to materialize a `GhostSession`.
3. Sets state to `AWAITING_HELLO`.
4. Starts heartbeat (which also arms the handshake timeout).
5. Emits the constant log message `"ws connected (sanitized)"`.

`handleBinaryMessage`:

1. Looks up the `GhostSession`. If absent (shouldn't happen), closes the WebSocket
   with `SERVER_ERROR`.
2. Touches `lastActivityMs`.
3. Applies per-connection command rate limiting. If exceeded → `ErrorCode.RATE_LIMITED_CONNECTION`
   with `retry_after_ms = 1000`.
4. Copies the frame bytes into a `byte[]` and decodes via `GhostEnvelopeDecoder`. Any
   `InvalidProtocolBufferException` → `ErrorCode.BAD_ENVELOPE`, then
   `GOODBYE(PROTOCOL_ERROR, "invalid protobuf")`.
5. Validates base envelope and type/payload match. Failure → `ErrorCode.UNSUPPORTED_VERSION`
   or `ErrorCode.BAD_METADATA`, recorded as a protocol violation.
6. Dispatches by `MessageType`.

Per-type dispatchers:

- `HELLO`: must be in `AWAITING_HELLO`. Validates nickname. Sets `displayName`
  (using the synthesized `anon-…` if blank), transitions to `ESTABLISHED`, sends
  `WELCOME` with the effective limits.
- `JOIN_ROOM`: must be in `ESTABLISHED` or `IN_ROOMS`. Resolves `roomId` (envelope
  field, falling back to payload). Validates. Enforces `max_rooms_per_connection`
  for new rooms. On success, joins the room registry, updates the session's room
  set, transitions to `IN_ROOMS`, replies with `ROOM_JOINED` carrying
  `onlineCount`.
- `SEND_ENCRYPTED_MESSAGE`: must be in `IN_ROOMS`. Validates room scope; checks
  membership (`ErrorCode.NOT_IN_ROOM` if absent); checks message rate limit
  (`RATE_LIMITED_CONNECTION`); validates payload. On success, sends
  `MESSAGE_ACCEPTED` to the sender and fans out `ENCRYPTED_MESSAGE` to every other
  participant of the same room. Recipients whose outbound queue is full are marked
  as slow clients and closed once with `GOODBYE(POLICY_ERROR, "client too slow")`.
- `MESSAGE_RECEIVED_ACK`: accepted in `IN_ROOMS`, discarded.
- `PONG`: updates `lastPongAtMs`.
- `DISCONNECT`: triggers `GOODBYE(CLIENT_REQUEST, "client requested")`.
- `PING`: ignored (the server drives heartbeats).
- Anything else: `ErrorCode.PROTOCOL_VIOLATION`.

`onProtocolViolation` emits the error envelope, then records the violation and, if
the session has crossed `max_violations_in_window` within `violation_window_ms`,
emits `GOODBYE(TOO_MANY_VIOLATIONS, "too many violations")`.

`send` serializes the envelope, checks the outbound-queue policy, increments
`onEnqueueOutbound` counters, writes via the decorated session, and decrements on
return. On runtime failure it returns `false`, which lets the fan-out loop treat the
recipient as a slow client.

`afterConnectionClosed`: marks the session `CLOSED`, calls
`roomRegistry.leaveAll(session)`, removes the session from the registry, logs
`"ws closed (sanitized)"`.

---

## 4. In-memory sessions

Every session state lives in process memory. The session registry is a
`ConcurrentHashMap<String, GhostSession>` keyed by `WebSocketSession.getId()` (a value
the servlet container generates per connection). Restarting the JVM destroys every
session.

There is **no** sticky session, no session affinity, no failover. A second instance
of the server would not know about sessions on the first instance.

Privacy-Max-relevant point: the registry never indexes sessions by IP, by header, by
display name, or by any client-supplied value. Lookup is by container-supplied
session id only.

---

## 5. In-memory rooms

Rooms live in `InMemoryRoomRegistry.rooms`, a `ConcurrentHashMap<String, Room>` keyed
by `room_id`. A `Room` keeps participants in a `ConcurrentHashMap<String, WebSocketSession>`
keyed by WebSocket session id.

- Rooms are created on first join via `computeIfAbsent`.
- Joining or leaving a room is O(1) on the participant map.
- Fan-out iterates over the room's participants.
- When a room becomes empty, it is removed from the registry; the next join with the
  same id creates a fresh `Room` instance.
- `roomRegistry.leaveAll(session)` is called on disconnect and walks every room
  removing the session from each. This is O(rooms-total) on the server; for the
  default `max_rooms_per_connection = 5` and modest room counts this is fine. For
  larger deployments a reverse index (`session → rooms`) would be a natural
  optimization. The `GhostSession.rooms()` set already maintains that index
  implicitly but `leaveAll` currently does not use it.

---

## 6. Routing

`MessageRouter.recipientsExcludingSender(roomId, senderSession)` returns the list of
`WebSocketSession` instances that should receive a fan-out frame. The server fans out
by iterating that list and, for each open recipient, looking up its `GhostSession`
and calling `send` on it. Recipients whose underlying session is closed are skipped.
Recipients whose outbound queue is full are marked slow and closed.

The router intentionally does **not** look at the wire envelope; it only takes the
target room id (resolved by the handler) and the sender's WebSocket session.

---

## 7. Validation

Validation is centralized in `ProtocolValidator` (see Section 3.6). Every inbound
envelope goes through `validateBaseEnvelope` + `validateTypeMatchesPayload` in the
handler before dispatch. Per-type validation (`validateNickname`, `validateRoomId`,
`validateSendEncryptedMessage`) runs inside each dispatcher.

The validator uses a single compiled regex (`^[\x21-\x7E]+$`) for ASCII-visible
checks. Failures are signaled via `ValidationException`, which carries a short
message that the handler maps to an `ErrorCode`.

---

## 8. Error mapper

`ProtocolErrorMapper.error(code, …)` builds a complete `GhostEnvelope` of type `ERROR`.

- **Canonical public message**: the mapper chooses `ErrorMessage.message` from its
  canonical `ErrorCode → message` table. This means **client input never becomes**
  `ErrorMessage.message`, even by accident.
- **Caller-provided strings**: a legacy overload that accepts a
  `reason`/`ignoredReason` string exists only for compatibility and **discards** the
  string (it is not used as the public message).
- **No “message-text mapping”**: the handler does not translate exception message
  text into codes, and the mapper does not translate client input into public
  messages. Both code selection and public error text are driven by `ErrorCode`.

---

## 9. Sanitized logger

`SanitizedProtocolLogger` is the **only** way the application code emits log lines.
Its methods take a single `String` argument that callers populate with constant
sanitized text. There are no placeholder formats, no client-input interpolation, and
no overloaded methods that take a `Throwable` plus a templated message containing
client data.

This is enforced by:

- Convention in the codebase (every caller passes a literal).
- The `PrivacyLogAuditIT` integration test, which captures every Logback event
  emitted by the `io.ghostbunker` logger (the application's own packages) at
  TRACE level during a real HELLO/JOIN/SEND flow and fails the build if any of
  those events leaks ciphertext, nickname, session/user id, IP, `localhost:`, a
  recognized header marker, or a 48+ character hex blob.
- The `PrivacyLoggingConfigurationTest` configuration test, which independently
  verifies that the third-party Logback categories (Tomcat, Coyote, Spring Web,
  Spring WebSocket) are pinned to WARN in `application.yml` and that
  `server.tomcat.accesslog.enabled` is explicitly `false`. The two tests together
  cover both the application code (audit) and the operational logging contract
  (configuration).

---

## 10. Tests

`src/test/java/io/ghostbunker/server` contains:

- **`GhostSessionUnitTest`** (Surefire). A unit test with a mocked `WebSocketSession`
  and a mutable `Clock`. Asserts that the per-connection rate limiter accepts
  `max_commands_per_minute` and `max_messages_per_minute` commands within a window,
  rejects the next one, and that advancing the clock by 60 000 ms reopens the
  window.
- **`PrivacyLoggingConfigurationTest`** (Surefire). Loads the full Spring Boot
  application context (without a web environment) and asserts that the
  Privacy-Max logging contract from `application.yml` is in effect:
  `org.apache.tomcat`, `org.apache.coyote`, `org.springframework.web`, and
  `org.springframework.web.socket` resolve to an effective Logback level at least
  as restrictive as WARN, and `server.tomcat.accesslog.enabled` is exactly
  `false`. This catches drift in the source configuration that would re-enable
  identifiable third-party output without anyone noticing.
- **`GhostBunkerWebSocketIT`** (Failsafe). End-to-end against a real Spring Boot
  server on a random port. Verifies the happy path (`HELLO` → `WELCOME` → `JOIN_ROOM`
  → `ROOM_JOINED` → `SEND_ENCRYPTED_MESSAGE` → `MESSAGE_ACCEPTED` + fan-out
  `ENCRYPTED_MESSAGE` with matching ciphertext bytes), and the deliberate
  invalid cases listed in section 14 of the protocol spec.
- **`HeartbeatIT`** (Failsafe). Tightens `ping-interval-ms`, `pong-timeout-ms`, and
  `idle-timeout-ms` via `@TestPropertySource` and asserts that the server sends a
  `PING` envelope with a non-blank nonce within the heartbeat window, and that the
  connection remains open while heartbeats flow.
- **`PrivacyLogAuditIT`** (Failsafe). Runs a full HELLO/JOIN/SEND flow with
  sentinel values injected as the nickname and the ciphertext. Attaches a Logback
  `ListAppender` to the `io.ghostbunker` logger only (not root) and raises that
  logger to TRACE. Asserts that no captured event from any class under
  `io.ghostbunker.*` contains the sentinels, the ephemeral
  `session_id`/`user_id` from `WELCOME`, header markers (`User-Agent`,
  `Cookie`, `Authorization`, `Bearer`), loopback IPs, `localhost:`, or any
  continuous 48+ character hex blob. The scope is the application's own code;
  third-party logger configuration is covered by
  `PrivacyLoggingConfigurationTest`.
- **`TestEnvelopes`** and **`WsTestClient`**. Reusable test helpers: envelope builders
  and a Spring `StandardWebSocketClient`-based binary client that captures incoming
  binary frames into a `CopyOnWriteArrayList<byte[]>` for the integration tests to
  drain and decode.
- **`Manual*Client`** classes. Interactive clients intended to be run from an IDE
  with `main` methods, used for ad-hoc debugging against a locally running server.
  These are not invoked by `mvn verify` because their filenames do not match
  Failsafe's `**/*IT.java` include pattern.

Build invocation:

```bash
mvn clean verify
```

Runs `surefire` (unit) and `failsafe` (integration) phases. The `protobuf-maven-plugin`
runs in the `generate-sources` phase before compilation.
