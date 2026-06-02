# PDR-0000: &lt;decision title&gt;

> **Template only.** Copy this file to `NNNN-short-slug.md` and replace all placeholders. Do not implement wire changes until an accepted PDR exists.

## Metadata

| Field | Value |
|-------|-------|
| **Decision title** | &lt;short human-readable title&gt; |
| **Status** | `proposed` \| `accepted` \| `rejected` \| `superseded` |
| **Date** | YYYY-MM-DD |
| **Decision owner** | &lt;name or team — accountable for closure&gt; |
| **Decision makers** | &lt;names or roles who approved&gt; |
| **Protocol version affected** | &lt;e.g. GhostEnvelope `version` string, WebSocket subprotocol&gt; |

---

## Context

&lt;Why is this change needed? What problem does it solve? Link issues, review findings, or operational constraints. Summarize current v0.x behavior and what breaks if we do nothing.&gt;

---

## .proto changes

&lt;Concrete list of changes to `ghost_bunker_v1.proto`. Use field numbers and enum names.&gt;

- **Additions:** &lt;new fields / enum values; reserved range usage&gt;
- **Semantics:** &lt;any change to meaning of existing fields — requires version bump&gt;
- **Renumbering / reuse:** &lt;must be "none" or justify with new major version&gt;
- **Source of truth path:** `src/main/proto/ghost_bunker_v1.proto` (and future `ghost-bunker-protocol` repo if split)

---

## Privacy impact

&lt;Privacy-Max / threat-model impact. Answer explicitly:&gt;

- New **persistent** server fields or logs? &lt;yes/no — detail&gt;
- New **identifier-bearing** metrics or logs? &lt;yes/no — detail&gt;
- New metadata visible to server or infra? &lt;list fields&gt;
- Invite link / room key model affected? &lt;yes/no&gt;

Reference: [`docs/threat-model.md`](../threat-model.md), [`docs/privacy-max-profile-v0.1.md`](../privacy-max-profile-v0.1.md).

---

## Compatibility impact

&lt;Backward and forward compatibility.&gt;

- **Dual-stack required?** &lt;yes/no — duration&gt;
- **Breaking for existing clients?** &lt;yes/no — failure mode (`UNSUPPORTED_VERSION`, etc.)&gt;
- **Breaking for existing servers?** &lt;yes/no&gt;
- **Subprotocol change?** &lt;e.g. new `ghost-bunker.v0.2` vs same `ghost-bunker.v0.1`&gt;
- **Deprecation timeline:** &lt;dates or release trains&gt;

---

## Client impact

&lt;All client implementations that must change.&gt;

| Repo / client | Required changes |
|---------------|------------------|
| `ghost-bunker-web-client` | &lt;encode/decode, crypto, UI, invite link&gt; |
| Other clients | &lt;TBD&gt; |

- **Minimum client version:** &lt;tag or commit, if any&gt;
- **Crypto / AAD / `cipher_suite`:** &lt;unchanged / describe&gt;

---

## Server impact

&lt;Reference server changes.&gt;

- **Validation:** &lt;`ProtocolValidator`, limits, state machine&gt;
- **Routing / persistence:** &lt;in-memory only; no new DB&gt;
- **Metrics:** &lt;aggregate-only; no forbidden tags&gt;
- **Configuration:** &lt;new `ghostbunker.limits.*` properties?&gt;

---

## Migration plan

&lt;Phased rollout per [`docs/protocol-migration-plan.md`](../protocol-migration-plan.md).&gt;

1. **Design freeze:** &lt;PDR accepted; spec updated&gt;
2. **Implementation:** &lt;server + client order&gt;
3. **Dual-run (if applicable):** &lt;T0, T_end, operator guidance&gt;
4. **Default cutover:** &lt;when old version is deprecated for new rooms / connections&gt;
5. **Documentation:** &lt;spec diffs, README, release notes&gt;

---

## Rollback plan

&lt;How operators and developers revert safely.&gt;

- **Server rollback:** &lt;pin image/jar; support previous `GhostEnvelope.version`?&gt;
- **Client rollback:** &lt;detect `WELCOME` / version; fallback behavior&gt;
- **Data / keys:** &lt;no server recovery of keys; client OOB rotation if needed&gt;
- **Operator action:** &lt;config flags, proxy, subprotocol&gt;

---

## Test matrix

&lt;Required checks before release. Extend rows from protocol-migration-plan §5 as needed.&gt;

| # | Server | Client | Scenario | Expected result |
|---|--------|--------|----------|-----------------|
| 1 | &lt;version&gt; | &lt;version&gt; | &lt;e.g. HELLO → WELCOME → JOIN → SEND&gt; | &lt;pass criteria&gt; |
| 2 | &lt;version&gt; | &lt;old client&gt; | &lt;upgrade without subprotocol&gt; | &lt;reject / downgrade&gt; |
| 3 | &lt;version&gt; | &lt;version&gt; | &lt;privacy: no forbidden log substrings&gt; | &lt;PrivacyLogAuditIT / manual&gt; |

**Automation:**

- [ ] `mvn clean verify` (server)
- [ ] `npm test` (web client, if affected)
- [ ] Load simulator smoke (if routing/limits changed)

---

## Audit requirement

| Question | Answer |
|----------|--------|
| **Independent cryptographic review required before claim?** | &lt;yes / no&gt; |
| **Reason** | &lt;e.g. changes ciphertext semantics, AAD, key agreement — not needed for additive optional wire fields with no crypto change&gt; |
| **Milestone** | &lt;link to `docs/releases/v1.0-protocol-cryptographic-review.md` if yes&gt; |

&gt; Completing this PDR does **not** create an audited or `v1.0` security tag.

---

## Decision log

| Date | Actor | Action |
|------|-------|--------|
| YYYY-MM-DD | &lt;owner&gt; | Opened as `proposed` |
| | | |

**Final decision:** &lt;one paragraph summary when accepted or rejected&gt;
