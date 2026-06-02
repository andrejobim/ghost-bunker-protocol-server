# Plan: future shared repository `ghost-bunker-protocol`

## Goal

Create a **shared, normative home** for Ghost Bunker Protocol artifacts so that:

- Server and clients can consume the **same** `.proto` and protocol docs.
- Compatibility and versioning rules live in one place.
- Generated-code guidance is standardized without committing generated code.

This document is a **plan only**. It does not move files yet and does not change any runtime behavior.

## Non-goals (for this change)

- No changes to `*.proto`
- No moving files between repositories
- No changes to server code or build
- No changes to any client code or build

## Proposed repository: `ghost-bunker-protocol`

### Why a separate repository

Today the server repository includes both:

- the **normative wire contract** (`ghost_bunker_v1.proto`)
- the **reference implementation** and its build-specific copies/layout

Separating a future `ghost-bunker-protocol` repository makes the protocol consumable by any implementation (server or client) without coupling to a particular language toolchain.

### Target contents (initial extraction)

The future repository should contain:

```
ghost-bunker-protocol/
  proto/
    ghost_bunker_v1.proto
  docs/
    ghost-bunker-protocol-v0.1.md
    protobuf-contract.md
    privacy-max-profile-v0.1.md
    protocol-migration-plan.md
    threat-model.md
    generated-code-notes.md
    compatibility-matrix.md
```

Notes:

- `proto/ghost_bunker_v1.proto` is the **only source of truth** for field numbers and wire shape (consistent with `docs/protobuf-contract.md`).
- The documentation files above are currently present in the server repo under `docs/` and should become **normative** once extracted.
- `generated-code-notes.md` and `compatibility-matrix.md` are new artifacts in the protocol repo that consolidate guidance currently split across repos and CI.

## Consumption model (server)

### Current state (do not change yet)

The server repository currently contains `.proto` in two locations:

- `src/main/proto/ghost_bunker_v1.proto` (build input for the server toolchain)
- `docs/ghost_bunker_v1.proto` (documentation copy referenced by docs)

These duplicates exist for practical reasons (build tooling + docs). They must not be removed until the server build is explicitly updated and verified.

### Future consumption options

The server should consume the shared proto in one of these ways (choose later):

1. **Git submodule / subtree (recommended first step)**
   - Add `ghost-bunker-protocol` as a submodule/subtree at `protocol/` (or similar).
   - Keep the server build reading from `src/main/proto/` initially by copying or symlinking during build.
   - Pros: simple, no artifact publishing required.
   - Cons: more git complexity; symlinks are awkward on Windows; still requires a build wiring change later.

2. **Published artifact (later)**
   - Publish `ghost-bunker-protocol` as a versioned artifact that contains only `proto/` + docs:
     - Maven: a `jar` containing `proto/` resources (for server builds)
     - Optional: a separate package for TypeScript generators if desired
   - Pros: clean dependency management; consumers pin versions.
   - Cons: requires release automation and a distribution channel.

3. **Vendoring snapshot in-server (transitional)**
   - Keep a pinned copy of `proto/ghost_bunker_v1.proto` vendored in the server repo with a script that updates it from the protocol repo at a specific tag.
   - Pros: minimal build changes at first.
   - Cons: easy to drift; requires discipline and CI checks.

### End state

When migration completes, the server repository should have exactly one authoritative proto input source, derived from `ghost-bunker-protocol` at a pinned version (tag or artifact version).

## Consumption model (web client)

### Current state (do not change yet)

The web client generates its bindings via `buf generate` and expects a `.proto` somewhere in its repo layout.

### Future consumption options

1. **Git submodule / subtree (recommended first step)**
   - Add `ghost-bunker-protocol` at `protocol/` in the web client repo.
   - Update `buf.yaml` / `buf.gen.yaml` (later, not in this plan change) to point at `protocol/proto/ghost_bunker_v1.proto`.

2. **Buf module / registry (later)**
   - Turn `ghost-bunker-protocol` into a Buf module so consumers can depend on it directly.
   - Pros: best-in-class for Protobuf sharing; strong version pinning.
   - Cons: introduces Buf module publishing workflow and policy decisions.

### End state

The web client pins the protocol via a versioned dependency (tagged git reference, submodule commit, or Buf module version) and regenerates bindings as part of its normal workflow.

## Versioning rules (protocol repo)

These rules should be documented in `ghost-bunker-protocol/docs/protobuf-contract.md` and `ghost-bunker-protocol/docs/protocol-migration-plan.md` and enforced by CI once the shared repo exists.

### Protocol identifiers

- **Wire version**: `GhostEnvelope.version` remains `"0.1"` for the current baseline.
- **WebSocket subprotocol**: `ghost-bunker.v0.1` remains the current baseline.
- **Protocol name**: `GhostEnvelope.protocol` remains `"ghost-bunker"` for the current baseline.

Any change to these identifiers is a **compatibility event** and must follow the migration plan.

### `.proto` change policy (high level)

- Additive changes (new optional fields, new enum values) are preferred.
- Field numbers are never reused.
- Semantics of existing fields must not change without an explicit version/migration decision.
- Reserved ranges remain reserved unless explicitly allocated with a recorded decision.

This matches the existing intent in `docs/protobuf-contract.md` and `docs/protocol-migration-plan.md`.

### Documentation versioning

- Documents that are part of the normative spec should be version-stamped in title and/or filename (e.g. `ghost-bunker-protocol-v0.1.md`, `privacy-max-profile-v0.1.md`).
- Documents that describe process and future work (`protocol-migration-plan.md`, `threat-model.md`) should still clearly declare the baseline version(s) they refer to.

## Tag rules (protocol repo)

Tags must make it easy for consumers (server and clients) to pin a protocol snapshot.

### Proposed tag scheme

- **Protocol snapshot tags**: `protocol-v0.1.0`, `protocol-v0.1.1`, ...
  - Represent the state of `proto/` and normative `docs/` together.
  - Do not imply security audit status.
- **Optional “audit” tags** (only after independent review criteria are defined and met): `protocol-v1.0-audited-YYYY-MM` (name TBD).

### Tag invariants

- A tag must correspond to a specific `.proto` checksum plus the matching normative docs.
- Consumers should not depend on `main` for production; they should pin a tag (or artifact version).

## Compatibility rules (what “compatible” means)

The protocol repo should define compatibility along two axes:

### 1) Transport compatibility

- WebSocket endpoint uses **binary frames** carrying exactly one serialized `GhostEnvelope` per frame.
- WebSocket upgrade negotiates the expected subprotocol (currently `ghost-bunker.v0.1`).

### 2) Wire compatibility

- Envelopes validate `protocol="ghost-bunker"` and `version="0.1"` (baseline).
- Unknown fields must be ignored (forward compatibility) and must not crash parsers.
- Implementations must fail fast with `UNSUPPORTED_VERSION` (or equivalent behavior) when encountering a version they do not support.

### 3) Crypto-profile compatibility (client-side)

Crypto details can evolve independently of the server as long as the **wire contract** remains unchanged, but clients must not assume that “same room id” implies decryptability:

- Different clients may produce ciphertext for the same `room_id` that is undecryptable by others unless they share the same key material and AAD construction.

## Migration steps (extracting into `ghost-bunker-protocol`)

### Phase 0 — Preparation (now)

- Add `docs/shared-protocol-repository-plan.md` (this file) to capture the plan and constraints.
- Identify all protocol artifacts to extract:
  - `.proto` source of truth target: `proto/ghost_bunker_v1.proto` in the future repo
  - normative docs listed above
  - references in server/client READMEs that should eventually point to the shared repo

### Phase 1 — Create `ghost-bunker-protocol` (no consumer changes yet)

- Create new repository `ghost-bunker-protocol`.
- Copy (do not move yet) the listed files into the new repo in the target layout.
- Add `docs/generated-code-notes.md` describing:
  - how to generate Java/TS bindings
  - what generator versions are known-good
  - what is explicitly not committed (no generated code, no binaries)
- Add `docs/compatibility-matrix.md`:
  - server version × client version matrix
  - required transport settings (subprotocol, path)
  - known incompatibilities (e.g., v0.2 keying vs older passphrase clients)
- Tag initial snapshot `protocol-v0.1.0`.

### Phase 2 — Add “pinned protocol” to consumers (still keep local copies)

- Server repo:
  - Add the protocol repo as a submodule/subtree (or vendor script) pinned to `protocol-v0.1.0`.
  - CI check: ensure the local `.proto` copy matches the pinned shared proto (checksum compare).
- Web client repo:
  - Add the protocol repo as a submodule/subtree pinned to `protocol-v0.1.0`.
  - CI check: ensure generation input `.proto` matches pinned shared proto (checksum compare).

At this phase, the **build inputs remain unchanged** (server still uses `src/main/proto/...`, client still uses its current proto path). The goal is drift prevention.

### Phase 3 — Switch builds to the shared proto (explicit, tested change later)

Only after Phase 2 is stable:

- Server:
  - Update build configuration so the proto compilation step reads from the pinned protocol repo path.
  - Remove redundant in-repo proto copies only when:
    - the build no longer depends on them, and
    - documentation references are updated, and
    - CI verifies no drift.
- Web client:
  - Update `buf` config to point at the pinned protocol repo path.
  - Keep regeneration deterministic and CI-enforced.

### Phase 4 — Documentation consolidation

- Update server and client READMEs to point to the shared protocol repo for:
  - the normative protocol spec
  - protobuf contract rules
  - migration plan
  - threat model
  - privacy-max profile

## What must not move yet (explicitly)

Until consumer build changes are planned, implemented, and verified:

- Do **not** delete or relocate the server’s build-input proto at `src/main/proto/ghost_bunker_v1.proto`.
- Do **not** change the `.proto` contents or field numbers as part of extraction.
- Do **not** change server or client runtime code to “follow” the new repo.
- Do **not** commit generated code into the protocol repo.
- Do **not** add telemetry/analytics or any network calls to “check versions”.

## Open questions (to decide when implementing the extraction)

- **Distribution**: submodule/subtree vs published artifact vs Buf module.
- **Release authority**: who tags `protocol-v0.1.x` and what CI gates are required.
- **Single source of truth during transition**: whether the protocol repo becomes normative immediately, or remains a mirror until Phase 3.
- **Compatibility test automation**: where to host cross-repo integration tests (protocol repo vs server repo CI).

