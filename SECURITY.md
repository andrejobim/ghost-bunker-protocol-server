# Security Policy

## Status

This project is **experimental** software and a **reference implementation** of Ghost
Bunker Protocol v0.1. It has **not received an independent cryptographic or security
audit**, and the Privacy-Max profile it tries to apply is best-effort, not a guarantee.

Do not use this software for sensitive production communications.

## Supported versions

Only the current development line of `main` (versioned `0.1.0-SNAPSHOT`) is supported.
There are no published releases at this time. Older snapshots are not maintained.

## Reporting a vulnerability

If you believe you have found a security or privacy issue in this repository, please
report it privately. **Do not open a public GitHub issue with exploit details or with
information that could be used to reproduce an attack against running instances.**

Preferred reporting channel:

- Use GitHub's "Report a vulnerability" private advisory flow on this repository if
  available, or
- Open a public issue that contains only the high-level area of concern (e.g. "I
  believe there is an envelope-validation bypass") and request a private channel; the
  maintainer will follow up.

Please include, at minimum:

- The affected component (class, file, or wire-protocol path).
- The conditions required to trigger the issue.
- The expected versus observed behavior.
- Whether you have a proof-of-concept; if so, do not paste payloads that contain
  real ciphertext, real nicknames, or real session identifiers in any public location.

The maintainer will acknowledge receipt as soon as practical and will coordinate on a
fix and a disclosure timeline. Because this is an experimental project, the maintainer
cannot offer SLAs, bug bounties, or guaranteed response times.

## In scope

The following are considered in scope for security and privacy reports:

- The Protobuf contract in `proto/ghost_bunker_v1.proto` and the matching server
  validation in `ProtocolValidator`, `GhostEnvelopeDecoder`, and
  `GhostEnvelopeEncoder`.
- The WebSocket handler `GhostBunkerWebSocketHandler` and the state machine it
  enforces over `GhostSessionState`.
- The heartbeat, handshake-timeout, and graceful-disconnect logic in
  `HeartbeatService`.
- The in-memory session and room registries (`InMemorySessionRegistry`,
  `InMemoryRoomRegistry`, `Room`).
- The per-connection rate limiter (`InMemoryRateLimitStore`, `GhostSession`'s
  sliding-window counters) and outbound back-pressure policy
  (`DefaultBackpressurePolicy`).
- The sanitized error mapper (`ProtocolErrorMapper`) and sanitized logger
  (`SanitizedProtocolLogger`).
- Anything that would cause the server to leak, persist, or transmit information the
  Privacy-Max profile says it must not — including but not limited to client IP
  addresses, HTTP headers, nicknames, ephemeral `session_id`/`user_id` values,
  ciphertext bytes, or plaintext message content (which the server should never see
  in the first place).
- Default configuration values in `GhostBunkerProperties`/`application.yml` if they
  enable insecure behavior under realistic operator assumptions.

## Out of scope

The following are not considered vulnerabilities in this repository:

- The fact that the WebSocket runs over TCP and that the operator's network sees the
  client IP at the transport layer. This is a property of WebSocket/TCP, not of the
  Ghost Bunker Protocol. Hiding network identity is explicitly out of scope and is the
  client's responsibility (see the Privacy-Max profile).
- The absence of an account system, password recovery, message history, or offline
  delivery. These are deliberate design choices, not bugs.
- The fact that intermediate infrastructure (reverse proxies, CDNs, hosting providers,
  kernel-level packet capture) may retain identifiable network metadata that this
  application cannot control.
- The fact that running multiple instances does not federate rooms across nodes. The
  reference implementation is single-node by design.
- Findings against deployments that the maintainer does not operate. This repository
  publishes a reference server; operators are responsible for the security of their
  own deployments.
- Style, code-quality, or dependency-version-bump suggestions that have no security
  impact. These are welcome but should be filed as normal pull requests or issues.
- Cryptographic-design questions about the client's choice of cipher suite. The
  server treats `ciphertext` and `nonce` as opaque bytes; key derivation and AEAD
  construction are entirely client-side concerns.

## Security disclaimer

Ghost Bunker is provided on an "as is" basis without warranty of any kind, express or
implied. No claim is made that the implementation is free of vulnerabilities, that the
Privacy-Max profile is correctly enforced under every operator configuration, that the
cipher suite identifier in the wire protocol corresponds to a cryptographically sound
construction in any specific client, or that the system as a whole provides anonymity,
unlinkability, or forward secrecy.

The reference implementation will close connections, drop messages, refuse oversized
envelopes, and emit sanitized errors as documented. It will not, however, prevent an
operator, an intermediate proxy, a compromised endpoint, or a malicious client from
violating user expectations. Users and operators must reach their own informed
conclusions about the suitability of this software for their threat model.
