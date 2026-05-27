# Ghost Bunker — Protocol Compatibility & Migration Plan

This document defines how Ghost Bunker evolves from **experimental v0.1 wire** +
**v0.2 client keying** toward a future **v1.0** cryptographic profile **without breaking
deployments silently**.

It complements `docs/protobuf-contract.md` and the v1.0 cryptographic review milestone.

---

## 1. Principles

1. **No silent breakage.** Clients and servers with mismatched expectations must fail
   fast with `UNSUPPORTED_VERSION` or explicit capability negotiation — never decrypt
   garbage or route ambiguous frames.
2. **Formal version decisions.** Any change to `src/main/proto/ghost_bunker_v1.proto`
   requires a recorded decision: bump `GhostEnvelope.version`, new subprotocol, or new
   major profile document.
3. **Coupled releases.** Server and reference client ship compatible crypto/wire
   behavior in the same release train when behavior changes.
4. **Compatibility tests are mandatory** before tagging any release that changes
   interpretation of ciphertext, AAD, or envelope fields.
5. **Symmetric v0 model remains available** during a documented deprecation window if
   v1 profiles are introduced.

---

## 2. Current compatibility baseline

| Artifact | Version / identifier | Notes |
|----------|----------------------|-------|
| `GhostEnvelope.version` | `"0.1"` | Validated by server |
| WebSocket subprotocol | `ghost-bunker.v0.1` | Required in server prod profile |
| `GhostEnvelope.protocol` | `"ghost-bunker"` | |
| `CipherSuite` enum | `PBKDF2_HMAC_SHA256_AES_256_GCM` | Wire label; v0.2 client uses direct 32-byte key |
| Reference server | `0.4.x-SNAPSHOT` line | Opaque ciphertext routing |
| Reference web client | v0.2 E2EE | See `ghost-bunker-web-client/docs/e2ee-v0.2.md` |

**Important:** v0.2 client keying is a **client-only** change. Wire messages are still
v0.1 Protobuf. Old PBKDF2-passphrase clients (if any remain) are **not** wire-compatible
with v0.2 ciphertext for the same room unless they share the same key material.

---

## 3. Version negotiation (today)

```
Client                          Server
  | HELLO (supported_cipher_suites, limits)
  |------------------------------>|
  |<------------------------------| WELCOME (effective limits, session ids)
  | JOIN_ROOM / SEND_ENCRYPTED_MESSAGE (cipher_suite, key_id, ...)
```

Future v1.0 work should extend **HELLO / WELCOME** (or add a post-handshake capability
message) rather than overloading `cipher_suite` with unrelated semantics.

---

## 4. Migration phases (planned)

### Phase A — v0.x stabilization (prerequisite)

- Complete production-readiness items that do not change crypto (`docs/production-readiness-plan.md`).
- Maintain green `mvn verify` (server) and `npm test` (client).
- Document threat model (`docs/threat-model.md`) — **no security claim**.
- Optional: dedicated `ghost-bunker-protocol` repo for `.proto` only (future).

### Phase B — v1.0 design freeze

- Maintainer signs protocol version decision (template in §6).
- Update `.proto` only after decision; reserve field numbers per `protobuf-contract.md`.
- Publish normative spec diff (`docs/ghost-bunker-protocol-v1.0.md` — **future file**).
- Implement **compatibility shim** in server: accept old `version=0.1` and new version
  concurrently if dual-stack is required.

### Phase C — reference implementation

- Server: validate new fields structurally; still no decryption.
- Client: implement chosen v1.0 profile behind feature flag.
- Shared test vectors repo or `src/test/resources/crypto-vectors/` (TBD).

### Phase D — dual-run period

| Duration | Behavior |
|----------|----------|
| T0 | v1-capable client + server released; v0.1 symmetric still default |
| T0 + N weeks | Documentation urges upgrade; monitor error rates (`UNSUPPORTED_VERSION`) |
| T_end | Deprecate symmetric-only profile for **new** rooms; existing rooms per policy |

Exact N is a product/ops decision, not fixed here.

### Phase E — audit gate

- Independent review against frozen spec + implementation.
- Remediate findings; publish summary.
- Only then: tag e.g. `v1.0-audited-YYYY-MM` (name TBD — **not** `v1.0` until criteria met).

---

## 5. Compatibility test matrix (required before crypto release)

Run manually or automate in CI where feasible.

| # | Server | Client | Expected |
|---|--------|--------|----------|
| 1 | v0.4+ | v0.2 web | HELLO/WELCOME; join; encrypt/decrypt round-trip |
| 2 | v0.4+ | v0.2 web | Reject missing subprotocol `ghost-bunker.v0.1` (prod) |
| 3 | v0.4+ | old PBKDF2 client (if retained) | Decrypt only if same key material — document mismatch |
| 4 | v1 (future) | v0.2 web | `UNSUPPORTED_VERSION` or negotiated downgrade |
| 5 | v0.4+ | v1 (future) | Upgrade path per §4 Phase D |
| 6 | v1 | v1 | Full v1 profile: signatures, replay policy, rotation |

**CI minimum today:** rows 1–2 via existing integration tests + web client unit tests.
Expand matrix when v1 `.proto` lands.

### Suggested automated checks (future)

- Golden-vector tests: encrypt known plaintext → known ciphertext for fixed key/nonce.
- Cross-language decode of `GhostEnvelope` without crypto (wire only).
- Fuzz `ProtocolValidator` with malformed envelopes.

---

## 6. Protocol version decision record (template)

Copy and fill before any `.proto` change:

```markdown
## Protocol version decision

- **Date:**
- **Decision makers:**
- **Change summary:** (fields added / enum values / version string)
- **Backward compatibility:** (dual-stack yes/no; deprecation timeline)
- **Affected repos:** ghost-bunker-protocol-server, ghost-bunker-web-client, (protocol repo)
- **Compatibility tests:** (matrix rows)
- **Privacy-Max impact:** (new persistent fields? new logs?)
- **Crypto review:** (required before claim: yes)
```

Store completed records in `docs/protocol-decisions/` (create when first decision is made).

---

## 7. `.proto` change rules (summary)

From `docs/protobuf-contract.md`:

- Prefer **new enum values** and **optional fields** over renumbering.
- Never reuse field numbers.
- Reserved ranges in `GhostEnvelope` (fields 8–19) require explicit allocation plan.
- Changing semantics of an existing field without a version bump is **forbidden**.

---

## 8. Rollback strategy

- Server: support previous `GhostEnvelope.version` for one major release when possible.
- Client: detect server version from `WELCOME`; fall back to symmetric v0 profile.
- Operators: pin Docker image / jar version; no automatic protocol migration at deploy.

---

## 9. Related documents

- [`threat-model.md`](threat-model.md)
- [`crypto-roadmap.md`](crypto-roadmap.md)
- [`releases/v1.0-protocol-cryptographic-review.md`](releases/v1.0-protocol-cryptographic-review.md)
- [`protobuf-contract.md`](protobuf-contract.md)
- Web client: [`ghost-bunker-web-client/docs/e2ee-v0.2.md`](../../ghost-bunker-web-client/docs/e2ee-v0.2.md)
