# Ghost Bunker Privacy-Max Profile v0.1

This document describes the **operational profile** that the Ghost Bunker reference
server attempts to apply. It is a best-effort profile, not a guarantee. It has not
been independently audited.

The profile is informational. It does not replace a threat model. It documents what
the reference implementation tries to avoid persisting, what it cannot avoid at the
transport layer, and what is explicitly out of scope.

The profile is versioned at `v0.1` alongside the protocol.

---

## 1. Purpose

Privacy-Max is the most restrictive operational mode the reference server targets. Its
intent is to minimize the information the server can produce, retain, share, or be
compelled to share about its users, while still routing client-encrypted messages
between participants of the same room.

This profile is purely **server-side and operational**. It says nothing about what
clients do with the plaintext they handle, nothing about what intermediate
infrastructure logs, and nothing about cryptography.

---

## 2. What the application avoids persisting

The reference server is configured to **never** persist the following:

- Message ciphertext or plaintext. The server treats ciphertext as opaque bytes that
  exist only in memory long enough to be forwarded to other participants of the same
  room.
- Nonces, AAD versions, or any per-message metadata other than what is required for
  in-flight routing.
- Nicknames or display names beyond the lifetime of the WebSocket session that
  introduced them. `GhostSession.displayName` is cleared when the session is removed.
- Ephemeral identifiers (`session_id`, `user_id`) beyond the lifetime of the
  connection. There is no log, no audit trail, no database row, no file containing
  them.
- Room membership history. When a connection closes, its memberships are removed.
  When a room becomes empty, it is removed from the registry.
- Connection IPs, ports, headers, or User-Agent strings at the application level.
  The application reads none of these and writes none of these to logs or storage.

There is no database driver, no JDBC connection, no JPA entity, no Redis, no file
output of message-level data anywhere in the application code.

---

## 3. Technical limit: IP visibility at the transport layer

The protocol runs over WebSocket, which runs over TCP, which means the operator's
host kernel **necessarily** sees the client IP address at the transport layer. This
is a property of TCP, not of Ghost Bunker.

The application layer does not read the remote address, does not include it in any
field of `GhostEnvelope`, and does not log it through `SanitizedProtocolLogger`. The
`PrivacyLogAuditIT` integration test asserts that no log event produced during
HELLO/JOIN/SEND contains `127.0.0.1`, the IPv6 loopback `0:0:0:0:0:0:0:1`, or the
substring `localhost:`.

However, the operator must understand the following:

- The host operating system's kernel logs, firewall logs, and connection tracking
  tables may retain peer addresses. Disabling these is an OS-level task outside this
  application.
- The Servlet container (Tomcat) and the underlying TLS terminator have their own
  internal data structures that hold the remote address while the connection is
  open. Whether they write it to a log file depends on their configuration. The
  reference `application.yml` explicitly sets `server.tomcat.accesslog.enabled:
  false` so the embedded container does not write per-request access logs
  containing the client IP, request URI, User-Agent, or timestamps of every
  WebSocket upgrade. `PrivacyLoggingConfigurationTest` asserts this at build time.
  Operators must re-confirm the equivalent on their deployment after any change to
  logging or reverse-proxy configuration.
- Any reverse proxy, load balancer, CDN, or hosting-provider edge node in front of
  the server will see the client IP and may log it. Configuring those components is
  outside the scope of this application and outside the scope of this profile.
- The Privacy-Max profile cannot turn TCP/WebSocket into an anonymity network. A
  client that wants its network identity hidden from the operator must reach the
  server through an anonymity layer it controls (Tor, a VPN it trusts) at its own
  discretion. That is an entirely client-side concern.

---

## 4. Logs the application must not produce

The following logging is **forbidden** by this profile and is enforced by the
reference implementation:

- No `info`/`warn`/`error`/`debug` log line in the application code that contains the
  ciphertext, the nonce, or any decoded form of either.
- No log line containing the full nickname submitted by a client.
- No log line containing the full ephemeral `session_id` or `user_id` returned in
  `WELCOME`.
- No log line containing `User-Agent`, `Cookie`, `Authorization`, `Bearer`,
  `127.0.0.1`, `0:0:0:0:0:0:0:1`, or the string `localhost:`.
- No log line containing a continuous hexadecimal blob of 48 or more characters,
  which would suggest raw envelope bytes have been logged.

`PrivacyLogAuditIT` enforces all of the above as integration assertions, capturing
every Logback event during a full HELLO/JOIN/SEND flow at TRACE level with sentinel
values injected as nickname and ciphertext, and checking the captured log content for
the forbidden substrings and patterns.

### 4.1 What the application does log

The reference server's `SanitizedProtocolLogger` is the only application-level entry
point for logging. Its callers use **only constant sanitized strings**: e.g.
`"ws connected (sanitized)"`, `"ws closed (sanitized)"`,
`"heartbeat ping failed (sanitized)"`, `"handshake timeout error send failed (sanitized)"`.
None of these strings interpolate client input.

### 4.2 Frameworks Spring and Tomcat may log

The application cannot control all log output from third-party libraries.
Spring's WebSocket client, Spring's WebSocket session adapter, and Tomcat's
internal endpoint code may emit DEBUG- or TRACE-level lines that include socket
addresses, session IDs internal to the container, or upgrade-protocol details.

These are not produced by the application code in this repository. The reference
server's `application.yml` pins them at WARN so they are quiet by default:

```yaml
logging:
  level:
    root: INFO
    org.apache.tomcat: WARN
    org.apache.coyote: WARN
    org.springframework.web: WARN
    org.springframework.web.socket: WARN
```

It also explicitly disables Tomcat access logs:

```yaml
server:
  tomcat:
    accesslog:
      enabled: false
```

These settings are verified at build time by `PrivacyLoggingConfigurationTest`. If
an operator lowers any of these categories to INFO or below in their deployment,
the test would still pass (it inspects the shipped `application.yml`) but the
deployed behavior would no longer match the profile — the test catches drift in
the source configuration, not in operator overrides at the deployment layer.

Operators deploying this server in Privacy-Max mode must:

- Keep the shipped `application.yml` logging block intact, or document an
  equivalent that maintains the contract.
- Not lower any of `org.apache.tomcat`, `org.apache.coyote`,
  `org.springframework.web`, or `org.springframework.web.socket` below WARN at
  deployment time.
- Verify the resulting log output against the same forbidden-substring list as
  `PrivacyLogAuditIT` before accepting the deployment as Privacy-Max-compliant.

The application's complementary integration test, `PrivacyLogAuditIT`, audits
**only** events from the `io.ghostbunker` Logback logger (covering every class
under `io.ghostbunker.server.*` and the generated Protobuf package). Third-party
loggers are intentionally out of scope of that audit; the configuration test above
is what enforces them.

---

## 5. Anti-abuse without persistent identity

The reference server applies several anti-abuse measures **without** retaining any
identity:

- **Per-connection rate limits** in sliding 60-second windows:
  `max_commands_per_minute` (default 60, applied to every inbound frame) and
  `max_messages_per_minute` (default 20, applied to `SEND_ENCRYPTED_MESSAGE`).
  Implementation: `GhostSession.tryIncrementCommands` /
  `GhostSession.tryIncrementMessages`.
- **Envelope and ciphertext size caps**: `max_envelope_bytes` (default 64 KiB) and
  `max_ciphertext_bytes` (default 16 KiB). Implementation: `GhostEnvelopeDecoder`
  and `ProtocolValidator.validateSendEncryptedMessage`.
- **Per-connection room cap**: `max_rooms_per_connection` (default 5).
- **Protocol-violation budget**: `max_violations_in_window` (default 3 within
  `violation_window_ms` = 60 000 ms). Exceeding the budget triggers `GOODBYE` with
  reason `TOO_MANY_VIOLATIONS` and connection close. Implementation:
  `GhostSession.recordProtocolViolationAndGetCountInWindow`.
- **Handshake timeout**: connections that never produce a `HELLO` within
  `handshake_timeout_ms` are closed.
- **Pong timeout and idle timeout**: connections that fail to respond to `PING` or
  that go silent are closed.
- **Outbound back-pressure**: a session whose outbound queue exceeds
  `max_outbound_queue_messages` or `max_outbound_pending_bytes` is marked as a slow
  client and closed once with `GOODBYE` reason `POLICY_ERROR`.

All of the above operate on the **current WebSocket connection only**. None of them
fingerprint the client, none of them keep counters across reconnects, none of them
share state between sessions. Banning, reputation, IP-based throttling, and similar
measures are explicitly out of scope for this profile.

---

## 6. Items out of scope for the Privacy-Max core

The following are deliberately not in scope for this profile and would need to be
addressed by other layers (a client, a network operator, a separate security
review):

- **Account systems**, password recovery, email/phone verification, KYC, captchas:
  out of scope. The protocol has no notion of any of these.
- **Client-side privacy**: keystroke timing, screen recording, OS-level telemetry,
  the user's choice of client implementation, the security of the client's local
  storage. Privacy-Max does not extend below the WebSocket boundary.
- **Network anonymity**: hiding the client IP from the operator. Use Tor, a trusted
  VPN, or an equivalent overlay at the transport layer.
- **Cryptographic primitives and key management**: cipher-suite design,
  PBKDF2/HKDF parameters, AAD construction, key rotation, room-key distribution.
  These are entirely client-side responsibilities. The server treats the
  `cipher_suite` and `key_id` fields as opaque identifiers.
- **Traffic analysis defenses**: packet padding, cover traffic, batched delivery,
  delayed forwarding. The reference server delivers `ENCRYPTED_MESSAGE` frames as
  promptly as it can after fan-out.
- **Cross-instance federation**: routing rooms across multiple server instances.
  This server is single-node by design.
- **Persistent message history**: out of scope. The protocol does not deliver
  messages to peers that are not currently in the room.
- **Independent cryptographic and security audit**: required before this server can
  be responsibly described as anything stronger than experimental.
- **Provider-side legal compliance**: requests for data, subpoenas, geographic
  jurisdiction questions. These depend on the operator's deployment and are not
  addressed by the application.
