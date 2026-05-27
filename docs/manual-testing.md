# Manual Testing

This document describes how to bring up the reference server locally and exercise it
by hand. It complements the automated test suite (which runs under
`mvn clean verify`).

---

## 1. Starting the server

From the repository root:

```bash
mvn spring-boot:run
```

The server starts on `ws://localhost:8080/ghost-bunker` (configured in
`src/main/resources/application.yml`, `server.port=8080`). The application log line
`"ws connected (sanitized)"` will appear when a client connects.

To override defaults from the command line, use Spring Boot's environment-style
overrides, for example:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--ghostbunker.limits.handshake-timeout-ms=15000"
```

Stopping the server with Ctrl+C terminates every open WebSocket and discards every
in-memory session and room.

---

## 2. Testing with `wscat`

[`wscat`](https://github.com/websockets/wscat) is a generic interactive WebSocket
client. It is useful for confirming that the WebSocket endpoint accepts connections,
that the server initiates `PING` frames on the configured interval, and that an
oversized or garbage frame triggers a close.

Install:

```bash
npm install -g wscat
```

Connect:

```bash
wscat -c ws://localhost:8080/ghost-bunker
```

### 2.1 Why `wscat` cannot do a full protocol test

`wscat`'s interactive input is **text**. The Ghost Bunker protocol uses **binary**
WebSocket frames carrying **Protobuf-serialized** `GhostEnvelope` messages. You
cannot type a valid `HELLO` envelope into `wscat` and have the server accept it.

What you can usefully do with `wscat`:

- Confirm the WebSocket handshake succeeds against the path `/ghost-bunker`.
- Observe that the server emits binary frames on its own (you will see `wscat`
  surface them as hex / binary noise — these are `PING` envelopes once you trigger
  the heartbeat interval).
- Observe that if you do **nothing**, after `handshake-timeout-ms` the server sends
  binary `ERROR(HANDSHAKE_TIMEOUT)` and `GOODBYE(PROTOCOL_ERROR, "handshake timeout")`
  envelopes and closes the connection.
- Trigger a transport-level close by sending a text frame (the server ignores text
  but you confirm `wscat` is talking to the right endpoint).

For an actual round-trip through the protocol you need a Protobuf-aware client.

---

## 3. The bundled manual clients

Under `src/test/java/io/ghostbunker/server` the repository ships four interactive
Java clients that produce real `GhostEnvelope` binary frames. They are not invoked by
`mvn verify` because their filenames do not match Failsafe's `**/*IT.java` pattern;
they exist to be run from an IDE (or directly with the test classpath) against a
locally-running server.

All four expect the server at `ws://localhost:8080/ghost-bunker` by default.

| Class | Purpose |
|---|---|
| `ManualGhostBunkerClient` | Positive single-client flow: `HELLO` → `JOIN_ROOM` → `SEND_ENCRYPTED_MESSAGE`, echoes server `PONG` frames, prints incoming envelopes (with redacted ids). |
| `ManualGhostBunkerParamClient` | Configurable single-client (designed to be run as "Client A": joins a room, stays idle, listens for routed `ENCRYPTED_MESSAGE` frames). |
| `ManualGhostBunkerParam2Client` | Companion client (designed as "Client B": connects, joins the same room, sends an `ENCRYPTED_MESSAGE` so that A receives the routed copy). |
| `ManualGhostBunkerNegativeClient` | Selects one of several deliberately-invalid scenarios via a `Scenario` enum constant compiled at the top of the file. |

### 3.1 Running a manual client

From an IDE: open the class and run `main` with the test classpath. From the command
line, with a built target tree:

```bash
mvn -DskipTests package
java -cp "target/test-classes:target/classes:$(mvn -q -DincludeScope=test dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" \
  io.ghostbunker.server.ManualGhostBunkerClient
```

(The exact classpath construction depends on your environment. The easier path is to
launch from an IDE.)

### 3.2 Positive flow with two clients

To watch the protocol route an encrypted message end-to-end, run two clients in
parallel:

1. Terminal A: run `ManualGhostBunkerParamClient`. The class defaults to
   `NICKNAME = "Andre-A"`, `ROOM_ID = "sala1"`, `SEND_MESSAGE_AFTER_JOIN = false`,
   `KEEP_ALIVE_SECONDS = 60`. It will connect, send `HELLO`, wait for `WELCOME`,
   `JOIN_ROOM`, wait for `ROOM_JOINED`, then sit idle and print whatever the server
   sends.
2. Terminal B: run `ManualGhostBunkerParam2Client`. The class defaults to
   `NICKNAME = "Andre-B"`, the same `ROOM_ID = "sala1"`,
   `SEND_MESSAGE_AFTER_JOIN = true`. It will connect, complete the handshake and
   join, then emit a `SEND_ENCRYPTED_MESSAGE` whose `ciphertext` is a synthetic
   `"fake-ciphertext-from-…"` string.
3. The server replies to B with `MESSAGE_ACCEPTED`, and fans out
   `ENCRYPTED_MESSAGE` to A. A should print a line beginning with `SERVER ->
   ENCRYPTED_MESSAGE`.

This is exactly what `GhostBunkerWebSocketIT.hello_then_join_then_send_routes_without_decrypting`
verifies automatically; the manual clients let you watch it happen against the
running JVM.

### 3.3 Negative scenarios

`ManualGhostBunkerNegativeClient` selects one scenario at a time by uncommenting a
`SCENARIO` line at the top of the class. The defined scenarios are:

- `TEXT_FRAME` — sends a text frame.
- `INVALID_PROTOBUF_BYTES` — sends a binary frame containing non-Protobuf garbage.
- `WRONG_PROTOCOL` — `HELLO` with `protocol = "not-ghost-bunker"`.
- `WRONG_VERSION` — `HELLO` with `version = "0.2"`.
- `NICKNAME_WITH_EMOJI` — nickname `"bob🙂"`.
- `NICKNAME_NON_ASCII` — nickname `"josé"`.
- `JOIN_ROOM_BEFORE_HELLO` — `JOIN_ROOM` first.
- `SEND_ENCRYPTED_BEFORE_HELLO` — `SEND_ENCRYPTED_MESSAGE` first.
- `SEND_ENCRYPTED_BEFORE_JOIN_ROOM` — `HELLO` then `SEND` without `JOIN_ROOM`.
- `CIPHERTEXT_EMPTY` — `SEND_ENCRYPTED_MESSAGE` with empty ciphertext.
- `CIPHERTEXT_TOO_LARGE` — `SEND_ENCRYPTED_MESSAGE` with ciphertext larger than
  `max_ciphertext_bytes`.
- `NONCE_MISSING` — `SEND_ENCRYPTED_MESSAGE` with empty nonce.
- `KEY_ID_MISSING` — `SEND_ENCRYPTED_MESSAGE` with blank `key_id`.
- `ENC_SUITE_MISSING` — `SEND_ENCRYPTED_MESSAGE` with `CIPHER_SUITE_UNSPECIFIED`.

Expected server responses are documented in section 14 ("Tested invalid cases") of
the protocol specification. The negative client prints the received `ErrorCode`,
sanitized error message, and any subsequent `GOODBYE` so you can confirm the
behavior matches the spec.

---

## 4. Inspecting server output

The server's stdout/stderr should contain only:

- The Spring Boot startup banner (suppressed by `spring.main.banner-mode=off` in
  `application.yml`).
- A small number of application-level lines through `SanitizedProtocolLogger`:
  `"ws connected (sanitized)"`, `"ws closed (sanitized)"`,
  `"heartbeat ping failed (sanitized)"`, `"handshake timeout error send failed (sanitized)"`.
- Whatever Tomcat and Spring write at `INFO` level (none of which should contain
  ciphertext, nicknames, or session/user ids).

If you ever see a log line containing a nickname, an ephemeral identifier, the
ciphertext, or a hex blob of the envelope bytes, that is a bug: please file an
issue and reference `PrivacyLogAuditIT`.

---

## 5. Reproducing the integration tests manually

You can mirror what `GhostBunkerWebSocketIT` does without writing any code:

1. Start the server (`mvn spring-boot:run`).
2. Run `ManualGhostBunkerClient` and observe the full positive flow.
3. Stop `ManualGhostBunkerClient`.
4. Edit `ManualGhostBunkerNegativeClient` and pick a scenario.
5. Run it and confirm the server response matches the expected `ErrorCode` and
   `GOODBYE` reason in the protocol spec, section 14.
6. Repeat step 4–5 for any other scenario you want to verify.
