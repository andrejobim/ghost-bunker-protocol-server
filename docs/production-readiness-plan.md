# Production Readiness Plan

This document describes what would have to change before the Ghost Bunker reference
server could be responsibly described as anything stronger than **experimental**.

It is not a release schedule. It is a list of concerns the current code does not yet
address, organized into phases. Items inside a phase are not strictly ordered.

The reference server is **not** production-ready today. Even with every item in this
document completed, the server would still require an **independent cryptographic
and security audit** before any non-experimental release.

---

## Phase 1 — Honest baseline

Before discussing readiness for anyone other than the protocol's authors, the current
code should be cleaned up to match its claims.

- **Allowed origins (DONE)**. `ghostbunker.websocket.allowed-origins` is configurable;
  `WebSocketConfig` passes the list to `setAllowedOrigins`. Development defaults to
  `*` in `application.yml`; the `prod` profile defaults to an empty list until
  `GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS` is set. Staging operators must still
  choose origins that match their deployed web client.
- **TLS (documented, not in-JVM)**. `application.yml` does not configure TLS. The
  reference server expects TLS termination at a reverse proxy or edge. See
  [`docs/reverse-proxy-deployment.md`](reverse-proxy-deployment.md) for ports
  (`8080` / `8081`), `wss://<host>/ghost-bunker`, WebSocket upgrade requirements,
  and proxy access-log policy. Development `ws://localhost:8080/ghost-bunker` remains
  local-only.
- **Bind address (partial)**. `application-prod.yml` sets `server.address: 0.0.0.0`
  for container use; reachability should still be restricted with network policy so
  only the proxy can reach `8080` and only monitoring can reach `8081`.
- **Shutdown (DONE)**. `GracefulShutdownService` broadcasts `GOODBYE` with reason
  `SERVER_SHUTDOWN` before exit; covered by `ProductionHardeningIT`.
- **Default logger levels (DONE)**. `application.yml` pins the third-party categories
  that can emit identifiable transport-layer details to WARN:
  `org.apache.tomcat: WARN`, `org.apache.coyote: WARN`,
  `org.springframework.web: WARN`, `org.springframework.web.socket: WARN`. The root
  logger stays at INFO. `server.tomcat.accesslog.enabled` is explicitly set to
  `false`. The contract is enforced at build time by
  `PrivacyLoggingConfigurationTest`. The integration audit `PrivacyLogAuditIT` is
  scoped to the application logger `io.ghostbunker` only, so it audits what the
  Ghost Bunker code itself emits and does not capture third-party noise.

---

## Phase 2 — Hardening

These are concrete safety measures the reference server should grow before reaching
real users.

- **Subprotocol enforcement in all non-dev deploys**. The `prod` profile requires
  `ghost-bunker.v0.1`; confirm every staging client and load tool sends
  `Sec-WebSocket-Protocol` (see `ProductionHardeningIT`).
- **Stricter input bounds**. The current validator covers obvious cases. Additional
  hardening:
  - Apply a maximum length to `Hello.client_name` (currently unbounded other than
    the envelope size limit).
  - Apply a maximum length to `message_id`, `request_id`, `client_message_id`,
    `key_id`, and `server_message_id`. Today these are bounded only indirectly via
    `max_envelope_bytes`.
  - Reject `room_id` values that are unprintable in subtle ways the
    `[\x21-\x7E]+` regex allows but are operationally surprising (e.g. all-digit ids
    that collide with anti-abuse tooling). Decide whether the protocol restricts
    `room_id` shape further.
- **Error-message canonicalization (DONE)**. `ErrorMessage.message` is now a **public,
  canonical** string derived from `ErrorCode` via a fixed table owned by
  `ProtocolErrorMapper`. Client input never becomes `ErrorMessage.message`, and the
  mapper does not use caller-supplied strings as the public message.
- **Goodbye-message canonicalization on the wire**. `HeartbeatService.closeWithGoodbye`
  currently writes the caller-supplied `sanitizedMessage` verbatim. Switch to using
  `GoodbyeReasonMessages.canonical(reason)` so the wire content is fully predictable
  and never reflects caller input.
- **Reverse session→room index in `leaveAll`**. Today the room registry iterates
  every room on disconnect to remove the leaving session. A per-session room set
  already exists (`GhostSession.rooms()`); using it would avoid the O(rooms-total)
  walk on disconnect.
- **Single-use scheduler executor**. `HeartbeatService` uses
  `Executors.newScheduledThreadPool(1, …)`. A single thread is sufficient for the
  defaults but becomes a bottleneck under many simultaneous sessions. Decide on a
  sizing rule and expose it as a configuration property.
- **Per-room counters**. The fan-out loop opens and closes sessions implicitly via
  `recipient.isOpen()`. Adding a sanitized aggregate counter ("connections currently
  in room X = N") would help operators without identifying any user.
- **Documented threat model (DRAFT)**. See [`docs/threat-model.md`](threat-model.md).
  It must still be reviewed and updated by qualified cryptographers before any v1.0
  security claim. Related: [`docs/crypto-roadmap.md`](crypto-roadmap.md),
  [`docs/protocol-migration-plan.md`](protocol-migration-plan.md),
  [`docs/releases/v1.0-protocol-cryptographic-review.md`](releases/v1.0-protocol-cryptographic-review.md).

---

## Phase 3 — Reference web client

The protocol cannot be evaluated end-to-end without a real client. A minimal browser
reference client should:

- Implement the Protobuf envelope encoding/decoding using `protobufjs` (or the
  equivalent) compiled from the same `proto/ghost_bunker_v1.proto`.
- Implement `CIPHER_SUITE = PBKDF2_HMAC_SHA256_AES_256_GCM` end-to-end: derive a
  room key from a shared secret via PBKDF2-HMAC-SHA-256 with a documented iteration
  count and salt construction; encrypt with AES-256-GCM using fresh 12-byte nonces;
  build AAD from non-secret envelope fields with a documented serialization.
- Reply to server `PING` with `PONG` echoing the nonce.
- Display the sanitized error codes/messages and goodbye reasons the server emits.
- Handle reconnection cleanly: a new connection gets a new `session_id` and
  `user_id`, which is the expected behavior.
- Not include any telemetry, analytics, or persistent identifier of the user.

This is the artifact that makes the protocol observable for a non-author audience.

---

## Phase 4 — Staging

Before talking to real users, the reference server should be deployed to a staging
environment that exercises the full network path it expects in production:

- Real DNS, real TLS certificates, no localhost shortcuts.
- Deploy per [`docs/reverse-proxy-deployment.md`](reverse-proxy-deployment.md):
  `wss://<host>/ghost-bunker`, application port **8080**, management port **8081**
  internal-only, `SPRING_PROFILES_ACTIVE=prod`, explicit allowed origins, subprotocol
  `ghost-bunker.v0.1`, reverse proxy with WebSocket upgrade and **no** access logs
  that record IP, URI, body, or ciphertext.
- Real kernel-level firewall/connection-tracking configuration documented end to
  end, with an explicit statement of which logs the OS retains and for how long.
- Synthetic test load using the reference web client to confirm the protocol works
  through the proxy, not just on `ws://localhost:8080/ghost-bunker`.
- Repeat the Privacy-Max log audit against the staging deployment with actual log
  collection enabled: capture all logs from the application, the proxy, and the OS,
  and confirm none of them contain forbidden substrings.

---

## Phase 5 — Load testing

The reference server has no published performance characterization. Before any
production claim, it should be measured:

- **Connection scale**. How many concurrent WebSocket sessions can one node hold?
  What is the per-session memory cost? The current `GhostSession` carries seven
  atomics, a deque, two maps, and various scalar fields — measure the actual
  per-session footprint.
- **Fan-out cost**. With N participants in a room, what is the latency from
  `SEND_ENCRYPTED_MESSAGE` arrival to the last `ENCRYPTED_MESSAGE` send-complete?
  How does it scale with N?
- **Slow-client behavior**. Confirm that one slow recipient does not block fan-out
  to other recipients. The current handler iterates recipients sequentially; if
  one of them takes the full `send-time-limit-ms` to write, the loop blocks for at
  least that long. Decide whether fan-out should be parallelized.
- **Heartbeat overhead**. With many sessions, the single heartbeat scheduler
  generates many wakeups per second. Measure and decide whether the scheduler pool
  needs to grow.
- **Rate-limit accuracy**. The sliding window resets after 60 000 ms; confirm
  behavior under sustained high-rate clients matches expectations.
- **Test with realistic ciphertext sizes**. The default `max_ciphertext_bytes` is
  16 KiB; measure with payloads at, below, and just above the limit.

---

## Phase 6 — Observability without identification

Operators of a Privacy-Max service need to know whether the service is healthy
without learning anything about individual users. The reference server currently
has no metrics or tracing wiring at all. The path forward:

- Expose **aggregate counters** only, never per-connection identifiers. Examples:
  - Total connections opened/closed (counter).
  - Currently active connections (gauge).
  - Total rooms currently active (gauge).
  - Total bytes routed (counter), without per-room or per-session breakdown.
  - Errors emitted by `ErrorCode` (counter, labeled by code only).
  - `GOODBYE` emitted by `DisconnectReason` (counter, labeled by reason only).
  - Slow-client closes (counter).
- **No per-session labels**, no `session_id` in metric tags, no `user_id`, no
  `room_id`. Aggregating by room id would create a covert index of room membership
  and must not exist.
- **No distributed tracing** that carries per-user identifiers across hops.
- **No third-party SaaS exporter** that would receive the metric stream by default.
- Document explicitly which metrics are exported and confirm by inspection that
  none of them leak identity. Repeat after every dependency upgrade.

---

## Criteria for a v0.1 (non-experimental) release

The reference server should not be described as anything stronger than experimental
unless **all** of the following are true:

1. Every item in Phase 1 (honest baseline) is complete and documented.
2. The hardening items in Phase 2 are complete or have explicit, accepted
   rationales for being deferred.
3. A reference web client (Phase 3) exists, is open-sourced, and has been used
   end-to-end against the server in a staging deployment.
4. A staging environment (Phase 4) has been validated, including a re-run of the
   Privacy-Max log audit against real logs from the application, proxy, and OS.
5. Load testing (Phase 5) has produced documented numbers, and the architecture
   choices match the measured behavior.
6. Observability (Phase 6) is in place with documented, identity-free metrics.
7. **An independent cryptographic and security audit** has been completed, with
   findings publicly summarized and remediations applied or explicitly tracked.
8. A documented incident-response and disclosure policy is in place (see
   `SECURITY.md`).
9. The `.proto` contract is stable enough to be tagged `v0.1` as a release rather
   than a development snapshot, with a written compatibility statement for any
   future v0.x change.

Until then, the project status is **experimental, work in progress, best-effort
Privacy-Max profile, reference implementation only**.
