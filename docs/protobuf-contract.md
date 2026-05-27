# Protobuf Contract

The wire contract of Ghost Bunker Protocol v0.1 is defined entirely by the file
`proto/ghost_bunker_v1.proto`. This document explains the contract in plain English,
field by field, and records the compatibility rules implementations must follow.

The `.proto` file is the source of truth. If this document and the `.proto` disagree,
the `.proto` wins.

---

## 1. Why Protobuf and not gRPC

The `.proto` file declares `syntax = "proto3"` and defines messages and enums only.
It does **not** declare a `service` block, it does **not** declare any `rpc` methods,
and it does **not** generate gRPC stubs.

The reason is structural:

- Ghost Bunker is a **bidirectional, long-lived, server-driven** WebSocket
  conversation, not a request/response RPC API. The server pushes
  `ENCRYPTED_MESSAGE`, `PING`, `ERROR`, and `GOODBYE` frames without a corresponding
  client-initiated call. gRPC's request/response and streaming models do not fit
  cleanly onto this shape, and even gRPC bidi streaming would require a heavier
  framing layer than the protocol needs.
- The transport is WebSocket binary frames. gRPC over HTTP/2 brings its own framing,
  status, and metadata model that would compete with the envelope-based design.
- The protocol must be implementable in browsers without specialized libraries.
  Hand-rolled Protobuf decoding over WebSocket has minimal dependencies; full gRPC
  in a browser requires gRPC-Web and a proxy.

Protobuf is therefore used **only as a serialization format**: every WebSocket binary
frame contains exactly one serialized `GhostEnvelope`.

---

## 2. The `GhostEnvelope` wrapper

`GhostEnvelope` is the single message type that appears directly on the wire. Every
inbound and outbound frame is a `GhostEnvelope`, with a typed payload inside the
`oneof payload` block.

```proto
message GhostEnvelope {
  string protocol = 1;          // "ghost-bunker"
  string version = 2;           // "0.1"
  string message_id = 3;        // per-envelope unique id
  uint64 timestamp_ms = 4;      // sender-side ms since epoch
  MessageType type = 5;         // must match the populated oneof branch
  string request_id = 6;        // optional correlation id
  string room_id = 7;           // optional, room-scoped messages

  oneof payload {
    Hello hello = 20;
    Welcome welcome = 21;
    JoinRoom join_room = 22;
    RoomJoined room_joined = 23;
    LeaveRoom leave_room = 24;
    RoomLeft room_left = 25;
    SendEncryptedMessage send_encrypted_message = 26;
    MessageAccepted message_accepted = 27;
    EncryptedMessage encrypted_message = 28;
    MessageReceivedAck message_received_ack = 29;
    Ping ping = 30;
    Pong pong = 31;
    ErrorMessage error = 32;
    Disconnect disconnect = 33;
    Goodbye goodbye = 34;
  }

  reserved 8 to 19;
}
```

Notes:

- Field numbers 1 to 7 are common envelope metadata.
- Field numbers 8 to 19 are **reserved** for future additive growth at the envelope
  level (for example, an explicit AAD-version, an explicit cipher-suite hint, a
  server-time field, or routing scope fields). Senders must not use them. Parsers
  must accept frames that use them in the future as unknown fields without crashing.
- Field numbers 20 to 34 carry the typed payloads. The numbering is intentional:
  payloads start at 20 to leave room at low numbers for future envelope metadata.

### 2.1 `protocol` and `version`

These are checked strictly by the reference server:

- `protocol` must equal `"ghost-bunker"`. Mismatch → `ErrorCode.BAD_METADATA`.
- `version` must equal `"0.1"`. Mismatch → `ErrorCode.UNSUPPORTED_VERSION`.

The expected values are configurable on the server side
(`ghostbunker.limits.expected-protocol`, `ghostbunker.limits.expected-version`) for
test harnesses, but the contract calls for the literal values above in v0.1.

### 2.2 `message_id`

A per-envelope unique identifier chosen by the sender. Recommended formats are UUIDs
or ULIDs, but the server treats this as an opaque non-empty string. It is not used as
a credential and is not persisted.

### 2.3 `timestamp_ms`

Sender-side milliseconds since the Unix epoch. The `.proto` explicitly states that
implementations **must not** rely on this value for trust decisions. The reference
server reads it from the wire but uses its own clock for all server-generated
timestamps (`Welcome.server_time_ms`, `MessageAccepted.accepted_at_ms`,
`EncryptedMessage.sent_at_ms`).

### 2.4 `type`

The `MessageType` enum value that **must** match the `oneof` branch actually
populated. `ProtocolValidator.validateTypeMatchesPayload` enforces this and rejects
mismatches with `BAD_METADATA`.

### 2.5 `request_id`

Optional correlation id, used for request/response flows:

- `SEND_ENCRYPTED_MESSAGE` ↔ `MESSAGE_ACCEPTED`: if the client sets `request_id`,
  the server copies it into the outgoing `MESSAGE_ACCEPTED` envelope and into the
  `MessageAccepted` payload.
- Server-emitted `ErrorMessage` may copy the inbound `request_id` when applicable.

### 2.6 `room_id`

Optional. Required on room-scoped messages. The reference server's
`ProtocolValidator.validateRoomId` enforces that it is non-blank, within
`max_room_id_chars`, and printable ASCII.

For `JOIN_ROOM`, the server prefers `GhostEnvelope.room_id` and falls back to
`JoinRoom.room_id` if the envelope-level field is empty.

---

## 3. Encrypted-payload messages

### 3.1 `SendEncryptedMessage` (inbound)

```proto
message SendEncryptedMessage {
  string client_message_id = 1;  // client-generated; correlation/dedup
  string key_id = 2;             // non-secret identifier for the room key
  CipherSuite cipher_suite = 3;  // must not be UNSPECIFIED
  bytes nonce = 4;               // 12 bytes for AES-GCM
  bytes ciphertext = 5;          // opaque to the server
  uint32 aad_version = 6;        // non-secret AAD construction version
  reserved 7 to 32;
}
```

Server-side validation in `ProtocolValidator.validateSendEncryptedMessage`:

- `ciphertext` must be present and non-empty.
- `ciphertext.size()` must not exceed `max_ciphertext_bytes` (default 16 384). Over
  the limit → `ErrorCode.CIPHERTEXT_TOO_LARGE`.
- `nonce` must be present and non-empty.
- `key_id` must be present and non-blank.
- `cipher_suite` must not be `CIPHER_SUITE_UNSPECIFIED`.

### 3.2 `EncryptedMessage` (outbound fan-out)

```proto
message EncryptedMessage {
  string server_message_id = 1;  // server-generated routing id
  string from_user_id = 2;       // sender's ephemeral user_id
  string key_id = 3;             // copied from SendEncryptedMessage
  CipherSuite cipher_suite = 4;  // copied
  bytes nonce = 5;               // copied
  bytes ciphertext = 6;          // copied verbatim
  uint64 sent_at_ms = 7;         // server's epoch ms at fan-out
  uint32 aad_version = 8;        // copied
  reserved 9 to 32;
}
```

The server never decrypts, modifies, or inspects `ciphertext`. It only copies it,
together with the AEAD parameters, to every other participant of the same room.

### 3.3 `MessageAccepted` (outbound to sender)

Sent to the **sender** as soon as the server has accepted the `SendEncryptedMessage`
for routing, before or after fan-out. Carries `request_id`, `client_message_id`,
`server_message_id`, `room_id`, and `accepted_at_ms`.

### 3.4 `MessageReceivedAck` (inbound, optional)

Carries `server_message_id`, `room_id`, `received_at_ms`. The reference server
accepts it in state `IN_ROOMS` and discards it. There is no persistence and no
fan-out. Read receipts that matter must be encoded inside the encrypted payload.

---

## 4. `ErrorCode`

```proto
enum ErrorCode {
  ERROR_CODE_UNSPECIFIED = 0;
  UNSUPPORTED_VERSION = 1;
  BAD_ENVELOPE = 2;
  BAD_METADATA = 3;
  PAYLOAD_TOO_LARGE = 4;
  CIPHERTEXT_TOO_LARGE = 5;
  TOO_MANY_ROOMS = 6;
  NOT_IN_ROOM = 7;
  HANDSHAKE_TIMEOUT = 8;
  PROTOCOL_VIOLATION = 9;
  CLIENT_TOO_SLOW = 10;
  RATE_LIMITED_CONNECTION = 11;
  INTERNAL_ERROR = 12;
}
```

Codes currently emitted by the reference server: `UNSUPPORTED_VERSION`,
`BAD_ENVELOPE`, `BAD_METADATA`, `CIPHERTEXT_TOO_LARGE`, `TOO_MANY_ROOMS`,
`NOT_IN_ROOM`, `HANDSHAKE_TIMEOUT`, `PROTOCOL_VIOLATION`, `RATE_LIMITED_CONNECTION`.

Reserved-but-unused: `PAYLOAD_TOO_LARGE`, `CLIENT_TOO_SLOW`, `INTERNAL_ERROR`,
`ERROR_CODE_UNSPECIFIED`. Future revisions of the reference server may begin using
these without changing the `.proto`.

The `ErrorMessage.message` field is a **public canonical message** derived from the
`ErrorCode`. It is intentionally short and sanitized, and **client input must never**
appear in this field (no nickname, room id, ciphertext, headers, IPs, or secrets).

In the reference server, the public message is chosen from a fixed `ErrorCode → message`
table owned by `ProtocolErrorMapper`; callers do not supply the public message text.

---

## 5. `DisconnectReason`

```proto
enum DisconnectReason {
  DISCONNECT_REASON_UNSPECIFIED = 0;
  CLIENT_REQUEST = 1;
  SERVER_SHUTDOWN = 2;
  IDLE_TIMEOUT = 3;
  PONG_TIMEOUT = 4;
  PROTOCOL_ERROR = 5;
  TOO_MANY_VIOLATIONS = 6;
  POLICY_ERROR = 7;
}
```

Used as `Disconnect.reason` (client → server) and `Goodbye.reason` (server → client).
The reference server's canonical sanitized `Goodbye.message` strings are mapped in
`GoodbyeReasonMessages.canonical` (see the protocol document, section 12). The actual
server code currently emits the caller-provided message string verbatim; the
canonical table documents the intended sanitized values.

---

## 6. `CipherSuite`

```proto
enum CipherSuite {
  CIPHER_SUITE_UNSPECIFIED = 0;
  PBKDF2_HMAC_SHA256_AES_256_GCM = 1;
}
```

In v0.1, the mandatory reference cipher suite is
`PBKDF2_HMAC_SHA256_AES_256_GCM = 1`. The server requires a non-unspecified value on
`SendEncryptedMessage.cipher_suite` but does not interpret the suite further — it is
copied through to recipients verbatim.

The semantic contract behind `CIPHER_SUITE = 1` (how clients derive a key, salt,
construct AAD, manage nonces) lives outside the wire contract and outside the
reference server.

---

## 7. Other messages

The remaining message types are straightforward and largely self-describing. Their
field semantics are documented inline in `proto/ghost_bunker_v1.proto`. The relevant
notes for implementers:

- `Hello.nickname` is validated as printable ASCII and bounded by
  `max_nickname_chars`.
- `Welcome.session_id` and `Welcome.user_id` are **ephemeral** random identifiers
  scoped to the connection. They are not credentials and must not be relied upon as
  durable identity.
- `Welcome.limits` mirrors the configured server-side `ProtocolLimits` and tells the
  client what the server will enforce.
- `Ping.nonce` is a fresh random string per heartbeat. The matching `Pong.nonce`
  must echo it.
- `ClientCapabilities` (inside `Hello`) is advisory. The reference server reads it
  but does not negotiate against it.

---

## 8. Compatibility rules

The protocol uses Protobuf 3 conventions. Implementers must follow these rules:

### 8.1 Safe additive changes

- **New fields** at unused field numbers in an existing message: safe. Old parsers
  treat them as unknown fields and pass them through.
- **New enum values**: safe to add. Receivers that do not recognize a value will see
  the wire number and should treat it as `UNSPECIFIED` semantics.
- **New message types** added to the `oneof payload`: safe at new, unused field
  numbers. Old parsers see them as unknown payloads.
- **Filling reserved field numbers** (envelope 8–19, payload tail ranges): allowed
  for additive growth, since `reserved` only blocks reuse, not first use.

### 8.2 Breaking changes

The following are **breaking** and require a version bump:

- Renaming a field while keeping the field number: semantically breaking even
  though the wire bytes do not change; tooling and code that references the field
  name will break.
- Reusing or repurposing an existing field number for a different type or meaning.
- Removing a field number that was previously emitted by senders in the wild.
- Renumbering existing fields.
- Changing the `protocol` or `version` strings, or changing the strict equality
  check the server applies.
- Removing a `MessageType`, `ErrorCode`, `DisconnectReason`, or `CipherSuite` enum
  value once it has been deployed.

### 8.3 Version negotiation

The current `GhostEnvelope.version` check is strict equality against the configured
expected version. There is no negotiation. Multi-version support, if introduced,
must be additive (e.g. the server accepts both `"0.1"` and `"0.2"` and selects the
intersection of supported behaviors), and the choice must be carried explicitly in
the envelope or in `Welcome`.

### 8.4 Cross-language code generation

The `.proto` declares `option java_multiple_files = true` and `option java_package =
"io.ghostbunker.protocol.v1"`. The Maven build's `protobuf-maven-plugin` generates
Java sources into that package. The file also declares `go_package`,
`csharp_namespace`, and `objc_class_prefix` to give consistent generated-code
layouts for other languages, though no non-Java code generation is currently wired
into this repository.
