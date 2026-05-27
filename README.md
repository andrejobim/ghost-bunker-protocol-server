# Ghost Bunker Protocol Server

Reference implementation (Java 21 + Spring Boot 3) of **Ghost Bunker Protocol v0.1**, a
WebSocket-based binary protocol for routing client-encrypted chat messages between
participants of the same room without any server-side identity, persistence, or message
history.

---

## Status

**Experimental / work in progress.**

- Not production-ready.
- No independent cryptographic audit has been performed.
- Privacy-Max compliance is best-effort, not a guarantee.
- Do not use this software for sensitive communications.

This repository is a reference implementation intended for protocol exploration, design
review, and manual testing. The wire contract (`proto/ghost_bunker_v1.proto`) and the
operational privacy profile (`docs/privacy-max-profile-v0.1.md`) are versioned at `v0.1`
and may still change.

---

## What this project is

- A reference WebSocket server that speaks the Ghost Bunker Protocol v0.1 binary
  envelope contract.
- A relay: it routes opaque ciphertext between participants of the same room without
  decrypting it.
- Configured to avoid logging or persisting message content, headers, IPs, nicknames,
  ephemeral session IDs, or user IDs in application-level logs (see Privacy-Max profile).
- Single-node, in-memory: sessions and rooms live only inside the running JVM.

## What this project is not

- Not a chat application. There is no UI, no account system, no message store, no
  delivery guarantees beyond best-effort fan-out to currently-connected peers in the
  same room.
- Not a cryptographic library. The server never decrypts, derives keys, or evaluates
  ciphertext. All key derivation, encryption, decryption, and AAD construction are the
  responsibility of the clients.
- Not gRPC. Protobuf is used only as the wire format inside binary WebSocket frames.
  The `.proto` file defines no `service` and no RPC methods.
- Not a transport-anonymity layer. WebSocket runs over TCP, so the operator's network
  inevitably observes the client IP address at the transport layer. Hiding network
  identity is out of scope and must be handled by the client (e.g. by tunneling over
  Tor or a VPN).
- Not a multi-node service. There is no clustering, no shared state, no cross-node
  routing. Running multiple instances would partition rooms across nodes.

---

## How it works (high level)

1. A client opens a WebSocket connection to `/ghost-bunker`.
2. The client sends a `HELLO` envelope. The server replies with `WELCOME`, including
   ephemeral `session_id`, `user_id`, effective protocol limits, and heartbeat
   interval. Both identifiers are generated per-connection and have no persistence.
3. The client joins one or more rooms with `JOIN_ROOM`. The server responds with
   `ROOM_JOINED` including the current online count.
4. The client sends ciphertext with `SEND_ENCRYPTED_MESSAGE`. The server replies with
   `MESSAGE_ACCEPTED` to the sender and broadcasts an `ENCRYPTED_MESSAGE` to every
   other participant of the same room.
5. The server drives heartbeats by sending `PING` at a fixed interval; clients reply
   with `PONG` echoing the nonce. Connections without recent activity, without a `PONG`
   within the timeout, or that never completed the handshake are closed with `GOODBYE`.

See [`docs/ghost-bunker-protocol-v0.1.md`](docs/ghost-bunker-protocol-v0.1.md) for the
full state machine and [`docs/protobuf-contract.md`](docs/protobuf-contract.md) for the
Protobuf contract.

---

## Requirements

- Java 21 (the `pom.xml` sets `maven.compiler.release` to 21 and targets the
  Spring Boot 3.5.x line).
- Apache Maven 3.9+.
- A working `protoc` toolchain; the build uses `protobuf-maven-plugin` 0.6.1, which
  downloads platform-specific `protoc` binaries via `os-maven-plugin`.

No external services are required. There is no database, no cache, no message broker.

---

## Building and running locally

Build and run all tests (unit + integration via Maven Failsafe):

```bash
mvn clean verify
```

Run the server:

```bash
mvn spring-boot:run
```

By default the server listens on `ws://localhost:8080/ghost-bunker`. The port is set in
`src/main/resources/application.yml` (`server.port=8080`).

Allowed origins are configurable via `ghostbunker.websocket.allowed-origins` (default
`*` in `application.yml`). For staging, use the `prod` profile and set explicit HTTPS
origins — see [`docs/reverse-proxy-deployment.md`](docs/reverse-proxy-deployment.md).

### Staging (Docker)

```bash
docker compose up --build
```

Activate `SPRING_PROFILES_ACTIVE=prod`, set `GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS`, and
terminate TLS at a reverse proxy. Metrics scrape: `http://127.0.0.1:8081/actuator/prometheus`.

Load smoke test (Protobuf clients, server must be running):

```bash
mvn -q -DskipTests package
java -cp "target/ghost-bunker-protocol-server-0.3.0-SNAPSHOT.jar;target/test-classes" \
  io.ghostbunker.server.load.GhostBunkerLoadSimulator ws://localhost:8080/ghost-bunker 20 30
```

Release notes: [`docs/releases/v0.3.0-alpha.md`](docs/releases/v0.3.0-alpha.md).

---

## Testing

The test suite contains:

- Two unit tests (Surefire):
  - `GhostSessionUnitTest`, which exercises the per-connection rate-limiter
    sliding window with a mutable clock.
  - `PrivacyLoggingConfigurationTest`, which loads the Spring Boot context
    (without a web environment) and asserts the Privacy-Max logging contract from
    `application.yml`: `org.apache.tomcat`, `org.apache.coyote`,
    `org.springframework.web`, and `org.springframework.web.socket` resolve to an
    effective Logback level at least as restrictive as WARN, and
    `server.tomcat.accesslog.enabled` is exactly `false`.
- Four integration test classes (Failsafe, `*IT.java`):
  - `GhostBunkerWebSocketIT` — full HELLO/JOIN/SEND flow, version negotiation,
    envelope validation, ciphertext size limit, too-many-rooms, send-before-join,
    handshake timeout, invalid Protobuf, repeated violations leading to disconnect.
  - `HeartbeatIT` — verifies that the server emits `PING` and that the connection
    stays open while heartbeats are exchanged.
  - `ProductionHardeningIT` — subprotocol enforcement, graceful `SERVER_SHUTDOWN`
    GOODBYE, and metrics tag privacy.
  - `PrivacyLogAuditIT` — runs the full HELLO/JOIN/SEND flow against a real Spring
    Boot WebSocket server with a Logback `ListAppender` attached at `TRACE` level
    to the `io.ghostbunker` logger (the application's own packages, not the root
    logger). Asserts that no captured log event contains sentinel ciphertext, the
    sentinel nickname, the ephemeral `session_id`/`user_id`, header markers
    (`User-Agent`, `Cookie`, `Authorization`, `Bearer`), loopback IP, `localhost:`,
    or any continuous hex blob of 48+ characters. Third-party logger
    configuration is the responsibility of `PrivacyLoggingConfigurationTest`.

For manual interactive testing using the bundled clients under
`src/test/java/.../Manual*Client.java`, see
[`docs/manual-testing.md`](docs/manual-testing.md).

---

## Configuration

All effective protocol limits are exposed under the `ghostbunker.limits.*` prefix and
defined in `GhostBunkerProperties` with the following defaults (see
`src/main/java/io/ghostbunker/server/config/GhostBunkerProperties.java`):

| Property | Default |
|---|---|
| `expected-protocol` | `ghost-bunker` |
| `expected-version` | `0.1` |
| `max-envelope-bytes` | 65 536 (64 KiB) |
| `max-ciphertext-bytes` | 16 384 (16 KiB) |
| `max-nickname-chars` | 32 |
| `max-room-id-chars` | 64 |
| `max-rooms-per-connection` | 5 |
| `max-messages-per-minute` | 20 |
| `max-commands-per-minute` | 60 |
| `max-outbound-queue-messages` | 100 |
| `max-outbound-pending-bytes` | 1 048 576 (1 MiB) |
| `handshake-timeout-ms` | 5 000 |
| `ping-interval-ms` | 30 000 |
| `pong-timeout-ms` | 10 000 |
| `idle-timeout-ms` | 90 000 |
| `max-violations-in-window` | 3 |
| `violation-window-ms` | 60 000 |
| `send-time-limit-ms` | 2 000 |

These can be overridden via `application.yml`, environment variables, or the standard
Spring Boot configuration mechanisms.

---

## Privacy-Max profile (best-effort, v0.1)

The Privacy-Max profile is documented in
[`docs/privacy-max-profile-v0.1.md`](docs/privacy-max-profile-v0.1.md). Summary of what
the reference server tries to enforce:

- No account system, no login, no signup, no password.
- No persistence: no database, no on-disk message history, no room directory.
- No long-lived identity. `session_id` and `user_id` returned in `WELCOME` are
  random UUIDs scoped to a single TCP connection.
- The server never sees plaintext. It treats `ciphertext` and `nonce` as opaque bytes.
- No fingerprinting field in the wire protocol. No `device_id`, no remote-address
  field, no User-Agent field. The `.proto` reserves these omissions explicitly.
- Application logs are produced only through `SanitizedProtocolLogger` with constant
  sanitized messages (`"ws connected (sanitized)"`, `"ws closed (sanitized)"`, etc.).
  `ErrorMessage.message` content is trimmed to 160 characters and intended to be a
  short sanitized string with no payload, IP, header, or secret.

What Privacy-Max **does not** promise:

- It cannot hide the client IP from the TCP/TLS stack. Operators of intermediate
  infrastructure (reverse proxies, CDNs, load balancers, kernel netfilter, hosting
  providers) can still observe network metadata that lives outside this application.
- It does not provide an independently audited cryptographic guarantee.
- It does not by itself defeat traffic analysis, timing correlation, or operator-side
  passive observation.

---

## Known limitations

- Single-node only. There is no distributed state, no broker, no cross-instance fan-out.
- All session and room state is in-memory and is lost on restart.
- The WebSocket endpoint accepts all origins (`*`). This is appropriate for local
  development only.
- There is no transport encryption configured by the application itself; `ws://` is
  used in development. Production deployments must terminate TLS in front of (or
  inside) the server.
- No persistent identity, no message delivery guarantees, no offline delivery, no
  receipts other than the optional `MESSAGE_RECEIVED_ACK` from the receiving client.
- Heartbeat, idle, and pong timeouts are global per connection and cannot be
  negotiated per peer.
- Backpressure detection on slow clients ultimately closes the connection with a
  `GOODBYE` of reason `POLICY_ERROR` and message `"client too slow"`.

---

## Roadmap

This is a non-binding outline of work the reference implementation may pick up next.
See [`docs/production-readiness-plan.md`](docs/production-readiness-plan.md) for the
fuller version.

- **Hardening**: tighten allowed origins, document a TLS termination story, add
  configuration for binding interface, add a structured shutdown hook that sends
  `GOODBYE` with reason `SERVER_SHUTDOWN`.
- **Web reference client**: a minimal browser client that performs PBKDF2-based key
  derivation and AES-256-GCM encryption matching `CIPHER_SUITE = 1`.
- **Staging environment**: a deployment that exercises the protocol behind a real
  reverse proxy and TLS.
- **Load testing**: characterize throughput, fan-out cost, and slow-client behavior
  under realistic conditions.
- **Observability without identification**: aggregate counters (active connections,
  rooms, errors per code) without per-connection identifiers.
- **Independent privacy and security review** as a prerequisite for any v0.1 release
  beyond the experimental tag.

---

## Documentation

- [`docs/ghost-bunker-protocol-v0.1.md`](docs/ghost-bunker-protocol-v0.1.md) —
  the protocol specification (transport, envelope, state machine, errors, limits).
- [`docs/privacy-max-profile-v0.1.md`](docs/privacy-max-profile-v0.1.md) —
  the operational privacy profile applied by this server.
- [`docs/protobuf-contract.md`](docs/protobuf-contract.md) —
  why Protobuf and not gRPC, plus a walkthrough of `GhostEnvelope`, the enums, and
  compatibility rules.
- [`docs/reference-server.md`](docs/reference-server.md) —
  the actual architecture of this Spring Boot server.
- [`docs/manual-testing.md`](docs/manual-testing.md) —
  how to run the server locally and exercise it with `wscat` and with the bundled
  manual clients.
- [`docs/production-readiness-plan.md`](docs/production-readiness-plan.md) —
  what is missing before this could responsibly be called production-ready.
- [`docs/privacy-audit.md`](docs/privacy-audit.md) —
  historical privacy audit notes kept for reference; **not** an authoritative
  description of the current code.

---

## License

See [`LICENSE`](LICENSE).
