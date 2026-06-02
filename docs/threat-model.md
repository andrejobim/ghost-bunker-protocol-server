# Ghost Bunker — Threat Model Freeze (v0.x baseline for external review)

This document is a **baseline threat model** for the Ghost Bunker Protocol v0.1 reference stack (server + reference clients) to support a future external cryptographic review.

It is intentionally conservative:

- It does **not** claim cryptographic security.
- It does **not** assert that v0.x is “secure”, “private”, or “end-to-end encrypted” in any audited sense.
- It describes **what is implemented today**, what is **explicitly out of scope**, and which threats are **not mitigated**.

## 1. System model and boundaries

### Components

| Component | Role | Notes / trust boundary |
|-----------|------|------------------------|
| Reference server | WebSocket relay that routes opaque ciphertext | Never decrypts; enforces envelope/state/limits; operator and infra may be hostile or misconfigured |
| Reference web client | Generates/imports a symmetric room key and encrypts locally | Runs on a potentially hostile endpoint (extensions, malware, compromised hosting) |
| Transport | `wss://` + WebSocket (binary frames) | TLS required for confidentiality/integrity in transit; does not provide anonymity |
| Operator infrastructure | Reverse proxy, host OS, CDN, logging pipeline | Not controlled by protocol; may log metadata even if app does not |
| Out-of-band channel | Invite link distribution | Key distribution is out of band; not defined by the v0.1 wire format |

### What the server does and does not see

The server is a **relay**. It necessarily processes some metadata for routing and validation, but it must not receive or derive secrets used for decryption.

## 2. Assets

Assets are listed here to make review scope explicit.

| Asset | Where it lives | Sensitivity |
|-------|----------------|-------------|
| **Message plaintext** | Clients only | High |
| **Room symmetric key (32-byte)** | Clients only | High (possession implies ability to decrypt and encrypt for the room) |
| **Ciphertext + nonce** | Wire; server memory during routing | Medium (opaque to server; becomes plaintext if room key leaks) |
| **Room identifier (`room_id`)** | Wire metadata; server routing tables | Medium (enables routing and correlation) |
| **Per-connection ephemeral ids (`session_id`, `user_id`)** | Server-generated; sent to clients | Medium (correlates activity for one connection lifetime) |
| **Message metadata (`message_id`, `request_id`, `timestamp_ms`)** | Wire metadata | Low–medium (can enable correlation/replay analysis; not secrets) |
| **Crypto hints (`cipher_suite`, `key_id`, `aad_version`)** | Wire metadata | Low–medium (not secrets; still metadata) |
| **Nickname / client_name** | Provided by clients | Low individually, but can be identifying in context |
| **Network identifiers (IP, TLS session, SNI, timing)** | Transport/infra | High for anonymity goals (explicitly not hidden by v0.x) |

## 3. Adversaries

This threat model assumes at least one of the following adversaries may exist.

### 3.1 Passive network observer

- **Capabilities**: observe endpoints, traffic timing/volume, TLS metadata; cannot break TLS.
- **Not mitigated in v0.x**: traffic analysis, timing correlation, endpoint correlation.
- **Mitigated only by**: correct TLS configuration (`wss://`) and general operational hygiene (not protocol-level anonymity).

### 3.2 Malicious network attacker (active)

- **Capabilities**: block, delay, reorder connections; attempt downgrade; inject packets (without TLS break).
- **Not mitigated in v0.x**: denial-of-service at the network layer; targeted blocking.
- **Mitigated only by**: TLS integrity (prevents undetected modification of frames), and operator-level redundancy/availability strategy (out of scope).

### 3.3 Malicious or curious server operator

- **Capabilities**:
  - sees all wire metadata required for routing and validation
  - can drop, delay, reorder, replay, and selectively forward frames
  - can run modified server code, add logging, or persist ciphertext
- **Hard limitation of v0.x**: the server is not trusted for confidentiality or availability; it is a relay.

### 3.4 Malicious participant (has the room key)

- **Capabilities**: decrypt all room traffic, encrypt arbitrary messages, share the key, capture invite links.
- **Not mitigated in v0.x**: forward secrecy, sender authenticity, key compromise containment.

### 3.5 Compromised client endpoint

- **Capabilities**: read JS memory, read clipboard, read URL bar/history/screenshots, exfiltrate plaintext and keys, alter UI to trick user.
- **Not mitigated in v0.x**: malware, hostile browser extensions, compromised hosting/CDN, supply-chain compromise.

## 4. In-scope threats (v0.x baseline)

These are threats the review should explicitly reason about, even if v0.x does not fully mitigate them.

- **Confidentiality vs server** (goal): plaintext should not be available to the server under the intended client crypto model.
- **Integrity vs network** (goal): on-path attackers should not be able to undetectably modify encrypted payloads if TLS + AEAD are used correctly by clients.
- **Replay and reordering** (partial): the system should not silently accept replays as “new” messages without detection/mitigation expectations being documented.
- **Metadata exposure and correlation**: define what metadata exists and how it can be used to correlate activity.
- **Key compromise impact**: define blast radius of a leaked room key (past/future messages).
- **Compromised hosting/client**: clarify what an attacker can do if they can run JS in the client origin.
- **Malicious server behavior**: document what a server can do even if it cannot decrypt.

## 5. Out-of-scope threats (explicit non-goals for v0.x)

These are not provided by the protocol and should not be inferred by users or reviewers.

- **Anonymity / unlinkability** (hiding IP, location, or traffic patterns).
- **Account security** (login, identity recovery, password reset).
- **Server-side key escrow or message recovery** (by design the server must not have keys).
- **Guaranteed delivery / offline storage** (no message history; relay only).
- **Multi-device key synchronization** (not defined).
- **Certification claims** (SOC2/FIPS/etc.) or “audited secure product” claims.

## 6. Metadata still visible to the server (and infrastructure)

Even with correct client-side encryption, the server (and often the reverse proxy) can observe:

- **Connection metadata**: connection open/close times; concurrent connection counts.
- **Envelope metadata fields** (v0.1): `protocol`, `version`, `type`, `message_id`, `timestamp_ms`, optional `request_id`, and `room_id` for room-scoped traffic.
- **Routing-relevant payload metadata** for encrypted messages: `key_id`, `cipher_suite`, `aad_version`, and the byte lengths of `nonce` and `ciphertext` (size-limited by the server).
- **Room membership**: which connections are in which rooms (for routing).
- **Rate limit / policy behavior**: which connections trigger rejections or disconnects.

Infrastructure can additionally see IP addresses and other network metadata regardless of application behavior.

## 7. Symmetric room-key model limitations (current v0.x)

The reference stack uses a **shared symmetric room key** distributed out of band (invite link model in the reference web client).

Limitations that reviewers should treat as baseline facts:

- **No forward secrecy**: a single room key can decrypt the entire room history that used it.
- **No post-compromise security**: if a key leaks, there is no ratchet to heal without manual rotation.
- **No sender authenticity**: symmetric encryption does not bind messages to a long-term identity; any key holder can impersonate any “nickname”.
- **Key distribution is out of band**: security depends on the channel used to share the invite link.
- **Key loss is unrecoverable**: if all participants lose the room key, the server cannot recover messages.

## 8. Invite link risks (reference web client model)

The invite link stores the room key in the URL fragment: `#gbkey=...`.

Baseline risks:

- **Link-as-secret**: anyone with the full link can decrypt and encrypt for that room.
- **Clipboard leakage**: copying links can expose them to clipboard managers or other apps.
- **Screenshots / screen sharing**: the URL bar may expose the fragment.
- **Browser history / sync**: some environments may retain or sync URLs (even if fragments are typically not sent in requests).
- **Referrer/analytics pitfalls**: third-party scripts or analytics can read `window.location.href` and exfiltrate `#gbkey`.

The fragment is **normally not sent** in HTTP requests or the WebSocket upgrade request, but the client origin and runtime environment remain critical.

## 9. Malicious server limitations (what the server cannot do, and what it still can)

### The server cannot (under intended design)

- Derive the room key from v0.1 protocol traffic (no key material is sent).
- Decrypt ciphertext without the key (assuming correct client crypto).

### The server still can

- **Withhold** messages (drop/fail to route) and cause selective denial-of-service.
- **Delay/reorder** frames, increasing confusion and enabling timing attacks.
- **Replay** previously observed ciphertext frames to clients.
- **Correlate** users by traffic timing, room membership, and metadata.
- **Log/persist** ciphertext and metadata if configured or modified to do so.

## 10. Compromised client limitations (what the protocol cannot protect)

If the client runtime is compromised (malware, hostile extension, compromised hosting, supply-chain):

- Room keys can be exfiltrated from memory, URL fragments, or clipboard.
- Plaintext can be exfiltrated before encryption or after decryption.
- UI can be modified to trick users into sharing secrets or joining wrong rooms.

Protocol design cannot “fix” a compromised endpoint; mitigation is operational (trusted builds, CSP, no third-party scripts, secure hosting).

## 11. Network observer limitations

Even with TLS and correct client crypto, a network observer can often infer:

- Who connected to which server and when
- Connection duration and message cadence
- Approximate message sizes (bounded by protocol limits)
- Room-level correlation via `room_id` if it is guessable or reused

Anonymity systems (Tor/VPN/mixnets) are outside the v0.x protocol.

## 12. Current v0.x limitations (summary)

The current baseline intentionally omits or only partially addresses:

- Forward secrecy / ratcheting
- Strong sender authentication / identity keys
- Robust anti-replay across sessions/devices
- Metadata minimization beyond the existing envelope contract
- Multi-device key sync, device management, revocation
- Anonymity and traffic-analysis resistance

## 13. Audit questions (for external review)

This section is a checklist of questions an external reviewer should be able to answer from the v0.x baseline and proposed v1.0 work, without assuming unimplemented guarantees.

### Protocol and metadata

- Which fields are visible to the server and which are strictly client-side only?
- Which fields are required for routing, and could any be removed or coarsened without breaking the model?
- What correlation is enabled by `room_id`, `message_id`, `request_id`, and timing?

### Cryptographic usage (client-side)

- Is AES-GCM used correctly (nonce uniqueness expectations, key import/export, failure handling)?
- Is the AAD construction well specified, versioned, and unambiguous?
- Are there any downgrade or confusion risks via `cipher_suite` or `aad_version`?

### Replay, ordering, and abuse

- What replay attacks are possible today, and what should clients do when they detect them?
- What are the DoS and resource-exhaustion vectors (handshake flooding, oversized frames, room churn)?

### Key distribution (invite link)

- What are the practical leak paths for `#gbkey` (browser history, extension access, analytics)?
- What mitigation guidance should be considered mandatory for static hosting (CSP, no third-party scripts, HTTPS)?

### Server/operator behavior

- What privacy guarantees depend on operator configuration (reverse proxy logs, access logs, retention)?
- What should be measured/verified to validate “Privacy-Max” behavior (logs, metrics tags, configs)?

