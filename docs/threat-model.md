# Ghost Bunker — Threat Model (v0.x baseline)

This document describes what Ghost Bunker Protocol **does and does not defend against**
today, and what assumptions future cryptographic work (v1.0+) must address. It applies
to the reference server, the reference web client, and any future standalone protocol
repository.

**Status:** Planning document for the v1.0 cryptographic review milestone. It does
**not** constitute a security proof, a penetration-test report, or an audited threat
model.

---

## 1. System boundary

| Component | Role | Trust assumption |
|-----------|------|------------------|
| Reference server | Routes opaque ciphertext; never decrypts | Semi-honest operator intent; implementation may have bugs |
| Reference web client | Derives/uses room key; encrypts locally | User-controlled endpoint; may be compromised |
| Transport (TLS + WebSocket) | Confidentiality/integrity of bytes in flight | TLS configured correctly; CA trust as usual |
| Operator infra (proxy, host, OS) | Terminates TLS, may log metadata | Not under protocol authors' control |
| Invite link / OOB channel | Distributes `room_id` + room key | Out of band; not defined by v0.1 wire format |

The server is a **relay**: it sees envelope metadata, room membership for routing, and
ciphertext blobs. It must not see plaintext, room keys, or passphrases.

---

## 2. Assets

| Asset | Owner | Sensitivity |
|-------|-------|-------------|
| Message plaintext | Clients | High |
| Room symmetric key (32-byte) | Clients | High — possession = read/write for room |
| `session_id`, `user_id` | Server (ephemeral) | Medium — correlates connections within server lifetime |
| `room_id`, `key_id`, `cipher_suite` | Wire metadata | Low–medium — routing and crypto hints, not secrets |
| Ciphertext + nonce | Wire | Medium — opaque to server; vulnerable if key leaks |
| Nickname (`client_name`) | Client → server | Low — display only; still identifying in a room |
| Network identifiers (IP, TLS session) | Infra | High for anonymity goals — **not hidden by protocol** |

---

## 3. Adversary classes

### 3.1 Passive network observer

- **Capability:** Observes traffic size, timing, TLS metadata, connection endpoints.
- **v0.x mitigation:** TLS (`wss://`); no plaintext on wire; server does not persist ciphertext.
- **Not mitigated:** Traffic analysis, correlation of connections to endpoints, room join/leave timing.

### 3.2 Malicious or curious server operator

- **Capability:** Full visibility of routing metadata, ciphertext, rate-limit behavior; can drop, reorder, or delay frames; can terminate connections.
- **v0.x mitigation:** Server design avoids decrypting; Privacy-Max logging rules reduce accidental retention.
- **Not mitigated:** Active manipulation of delivery order; traffic analysis; withholding messages; logging at reverse proxy; long-term storage if misconfigured.

### 3.3 Malicious participant in a room

- **Capability:** Holds valid room key (via invite link); decrypts all room messages; can encrypt and send arbitrary messages; can share key with others.
- **v0.x mitigation:** AEAD (AES-256-GCM) with per-message nonce; AAD binds non-secret fields.
- **Not mitigated:** No forward secrecy; no authentication of *which human* sent a message; compromise of one invite link compromises whole room history.

### 3.4 Compromised client endpoint

- **Capability:** Exfiltrates room key from memory, URL fragment, or clipboard; logs plaintext; substitutes ciphertext.
- **v0.x mitigation:** Client avoids logging secrets; optional fragment cleanup after import.
- **Not mitigated:** Malware, browser extensions, physical access, compromised build pipeline.

### 3.5 Replay attacker (wire or server)

- **Capability:** Captures `SendEncryptedMessage` / `EncryptedMessage` frames and re-injects them.
- **v0.x mitigation:** **Weak / partial.** Fresh nonces prevent naive AEAD reuse; server may dedupe by `client_message_id` within a session for acceptance acks, but **there is no global, cryptographically enforced anti-replay window** across sessions or clients.
- **Future (v1.0 roadmap):** Explicit replay counters, server-side deduplication policy, or ratchet message keys.

### 3.6 Infrastructure between client and server

- **Capability:** Reverse-proxy logs, WAF inspection of TLS metadata, CDN caching mistakes.
- **v0.x mitigation:** Documented deployment guidance (`docs/reverse-proxy-deployment.md`); access logs disabled by policy.
- **Not mitigated:** Provider-level visibility; misconfiguration by operator.

---

## 4. Security goals (target vs current)

| Goal | v0.1 wire + v0.2 client | Notes |
|------|-------------------------|-------|
| Confidentiality of plaintext from server | **Design intent** | Depends on correct client crypto; **not audited** |
| Integrity of ciphertext in transit | **Design intent** (AEAD) | Server does not verify AEAD — clients must |
| Authenticity of sender identity | **Not provided** | Nickname is self-asserted; no binding to long-term key |
| Forward secrecy | **Not provided** | Static room key until rotated manually |
| Post-compromise security | **Not provided** | No Double Ratchet |
| Replay resistance | **Partial / best-effort** | See §3.5 |
| Multi-device consistency | **Not defined** | Each browser holds key independently; no sync protocol |
| Anonymity / unlinkability | **Not provided** | Network layer visible; no mixnet |
| Denial of service resistance | **Partial** | Rate limits, size limits; not full abuse prevention |

---

## 5. Experimental v0.1 symmetric room-key model

The reference stack uses a **shared symmetric room key** distributed out-of-band (invite
link with `#gbkey=`). Wire identifier `cipher_suite = PBKDF2_HMAC_SHA256_AES_256_GCM`
remains for compatibility; the v0.2 client imports the 32-byte key directly (see web
client `docs/e2ee-v0.2.md`).

**Treat this model as experimental only:**

- No independent review of KDF usage, AAD construction, or nonce policy has been completed.
- No claim of "end-to-end encrypted product security" is valid until audit (see
  `docs/releases/v1.0-protocol-cryptographic-review.md`).
- Key compromise affects **all past and future** messages in that room until keys change.

---

## 6. Trust decisions clients must not make (today)

- Do **not** trust `timestamp_ms` for ordering or anti-replay.
- Do **not** trust `client_name` as cryptographic identity.
- Do **not** assume the server enforces uniqueness of `key_id` or prevents duplicate nonces across clients (clients must reject failed AEAD decrypts).
- Do **not** assume Privacy-Max guarantees hold on arbitrary operator deployments without log audit.

---

## 7. Out of scope (explicit non-goals for v0.x)

- Hiding client IP or physical location.
- Account recovery, password reset, or server-side key escrow.
- Offline message history or guaranteed delivery.
- Federated multi-node rooms (scaling interfaces exist; crypto model unchanged).
- Legal/compliance certifications (SOC2, FIPS, etc.).

---

## 8. Relationship to v1.0 review

Before any **stable v1.0** security claim:

1. This threat model must be reviewed and updated by qualified cryptographers.
2. Gaps in §4 (especially replay, forward secrecy, sender auth, multi-device) must be
   either implemented per `docs/crypto-roadmap.md` or explicitly accepted with written
   rationale.
3. Residual risks must be published in audit summary form.

Until then, use the disclaimer in `SECURITY.md` and describe the project as
**experimental**.
