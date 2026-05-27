# Ghost Bunker — Cryptographic Roadmap (evaluation only)

This document **evaluates** future cryptographic mechanisms for Ghost Bunker. It is
part of the **v1.0 protocol cryptographic review** milestone.

**Hard rules:**

- Do **not** implement the items below in v0.1 / v0.2 implementation prompts or releases.
- Do **not** claim cryptographic security until an **independent review** completes.
- Keep the v0.1 **symmetric room-key** model as **experimental only** until migrated.
- Do **not** change `ghost_bunker_v1.proto` without a formal protocol version decision.
- Do **not** ship server and client crypto changes independently without compatibility tests.

---

## 1. Current baseline (v0.x)

| Layer | Mechanism | Location |
|-------|-----------|----------|
| Transport | TLS 1.2+ (`wss://`) | Operator |
| Message | AES-256-GCM, 12-byte nonce | Client only |
| Key material | 32-byte random `room_key` (v0.2 client) | OOB invite link |
| Wire label | `PBKDF2_HMAC_SHA256_AES_256_GCM` | Historical; direct key import in v0.2 client |
| AAD | `ghost-bunker\|0.1\|room_id\|key_id\|suite\|aad_version` | Client only |
| Server | Opaque `ciphertext`, `nonce`, `key_id` | No crypto operations |

---

## 2. Roadmap overview

```text
v0.x (now)     Symmetric room key, experimental, wire v0.1 unchanged
     |
v0.y stabilize Reference server/client, compatibility tests, threat model draft
     |
v1.0-design    Protocol version decision, .proto changes (if any), migration spec
     |
v1.0-audit     Independent cryptographic review (blocking)
     |
v1.0-claim     Tag/release only after published audit + remediations
```

**No git tag** for v1.0 security until an audited milestone exists (see release note).

---

## 3. Candidate mechanisms

### 3.1 X25519 (ECDH)

**Purpose:** Establish shared secrets between participants without sending long-term
private keys over the wire.

| Aspect | Evaluation |
|--------|------------|
| Fit | Natural step before or alongside asymmetric sender keys; room join could include ephemeral ECDH payloads |
| Libraries | libsodium/TweetNaCl patterns; WebCrypto `X25519` where available |
| Wire impact | Likely new `cipher_suite` and/or handshake fields — **requires protocol version bump** |
| Risks | Invalid curve / low-order points if not validated; binding ECDH output to room/session context |
| v0.x | **Do not implement** |

**Open design questions:**

- Per-room static ECDH + symmetric wrap, or per-session ephemeral ECDH?
- How is the ECDH transcript mixed into AEAD keys (HKDF info string)?

---

### 3.2 Ed25519 (signatures)

**Purpose:** Authenticate message origin or long-term device/identity keys.

| Aspect | Evaluation |
|--------|------------|
| Fit | Addresses "anyone with room key can impersonate anyone" (threat model §3.3) |
| Wire impact | New fields: `signing_public_key`, `signature`, possibly `signed_payload_hash` |
| Server role | Server might route signatures opaquely or validate structure only — policy TBD |
| v0.x | **Do not implement** |

**Open design questions:**

- Sign plaintext before encrypt, or sign ciphertext + metadata (sign-then-encrypt vs encrypt-then-sign)?
- Key directory: per-device Ed25519 key vs per-room signing key?

---

### 3.3 Double Ratchet (Signal-style)

**Purpose:** Forward secrecy and break-in recovery for pairwise or small-group chats.

| Aspect | Evaluation |
|--------|------------|
| Fit | Strong match for 1:1 or small rooms; **heavy** for large broadcast rooms |
| Complexity | High — state per session, out-of-order handling, skipped-message keys |
| Wire impact | Major: message keys, header keys, possibly new message types for ratchet control |
| Group variant | May need Sender Keys or MLS for N>2 — separate decision |
| v0.x | **Do not implement** |

**Recommendation:** Treat Double Ratchet as a **v1.0+ optional profile** (e.g.
`ghost-bunker/1.0-ratchet`), not an extension of symmetric room-key mode.

---

### 3.4 Key rotation

**Purpose:** Limit exposure window when a room key leaks; support voluntary rotation.

| Aspect | Evaluation |
|--------|------------|
| Current | Manual: generate new `room_key`, new invite link; old ciphertext still readable with old key |
| Server | Could expose `key_id` rotation hints only; rotation is client-driven |
| Wire | `key_id` already present — document rotation semantics and client behavior |
| Future | Cryptographic re-key inside ratchet; or wrapped new keys distributed via old key |
| v0.x | **Document only** (manual rotation in `e2ee-v0.2.md`); no automated protocol |

---

### 3.5 Replay protection

**Purpose:** Prevent adversaries from re-submitting captured envelopes.

| Aspect | Evaluation |
|--------|------------|
| Current gaps | `timestamp_ms` untrusted; `client_message_id` dedupe is session-scoped at best |
| Client-side | Monotonic counters in AAD; reject duplicate `(key_id, nonce)` |
| Server-side options | Store short-lived `(room_id, client_message_id)` or `(room_id, counter)` bloom filter — **privacy trade-off** |
| Crypto | Ratchet includes implicit replay windows |

**Recommendation:** Define a **normative replay policy** in v1.0 spec before server
stores identifiers at scale (Privacy-Max implications).

---

### 3.6 Out-of-band (OOB) verification

**Purpose:** Detect MITM on key distribution (invite link, QR, voice).

| Aspect | Evaluation |
|--------|------------|
| Current | Invite link = trust on first use; no fingerprint UI |
| Future | Safety numbers (hash of long-term keys), QR compare, short authentication string (SAS) |
| Dependencies | Requires long-term or session-fingerprintable public keys (Ed25519 / X25519) |
| v0.x | **Do not implement**; document risk in threat model |

---

### 3.7 Multi-device model

**Purpose:** Same user on phone + desktop with consistent decrypt capability.

| Aspect | Evaluation |
|--------|------------|
| Current | Each device needs full `room_key` via separate invite — no sync |
| Options | (a) Per-device keys + server-assisted key wrap (reduces pure E2EE), (b) User-held backup key, (c) Pairwise provisioning via OOB |
| Wire | Likely `device_id` — **conflicts with Privacy-Max v0.1** unless carefully scoped |
| v0.x | **Not defined** |

**Recommendation:** Decide Privacy-Max stance before adding `device_id` to `.proto`.

---

### 3.8 Independent cryptographic audit

**Purpose:** External validation before any stable v1.0 security claim.

| Phase | Activity |
|-------|----------|
| Pre-audit | Freeze threat model, crypto roadmap, migration plan; complete v0.y compatibility suite |
| Auditor selection | Firm or researchers with messaging-protocol experience; clear scope boundary (client + server + proto) |
| Deliverables | Written report, severity-rated findings, remediation tracking |
| Publication | Summary public; details per coordinated disclosure |
| Gate | **No v1.0 security tag** until critical/high findings addressed or accepted with documented risk |

See `docs/releases/v1.0-protocol-cryptographic-review.md` for checklist.

---

## 4. Suggested v1.0 profiles (non-normative)

| Profile | Crypto stack | Use case |
|---------|----------------|----------|
| `symmetric-v0` (legacy) | Room key + AES-GCM | Backward compatibility only; deprecated after migration period |
| `dh-signed-v1` | X25519 + Ed25519 + AES-GCM | Small rooms, authenticated senders |
| `ratchet-v1` | Double Ratchet (+ optional group extension) | High-assurance 1:1 / small group |

Profiles must be negotiated explicitly (new `Hello` / `Welcome` fields or version string),
not inferred from `cipher_suite` alone.

---

## 5. Implementation gates (all future work)

1. **Protocol version decision** recorded (maintainer + document).
2. **`.proto` change** reviewed for field privacy and backward compatibility.
3. **Migration plan** (`docs/protocol-migration-plan.md`) updated with timelines.
4. **Reference server + web client** updated in the same release train.
5. **Compatibility tests** pass (matrix in migration plan).
6. **Audit** complete for the profile being claimed.

---

## 6. What v0.x may still do (non-advanced crypto)

Allowed without v1.0 audit claim:

- Documentation, threat modeling, test vectors for **current** AES-GCM + room key.
- Server hardening (rate limits, validation, privacy logging) — no new cipher suites.
- Client UX for manual key rotation (new invite link) without new wire fields.
- Clarifying disclaimers in README and SECURITY.md.

Forbidden in v0.x **implementation** prompts:

- X25519, Ed25519, Double Ratchet, automated ratchet state, server-side key escrow.
