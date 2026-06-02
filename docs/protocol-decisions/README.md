# Protocol decision records (PDRs)

This directory holds **Protocol Decision Records** for Ghost Bunker wire and contract changes. Each record documents a deliberate decision **before** implementation, so server, client, and future shared-protocol repos can stay aligned.

## When to create a PDR

Create a new PDR when any of the following apply:

- Changing `src/main/proto/ghost_bunker_v1.proto` (fields, enums, reserved ranges, semantics).
- Changing `GhostEnvelope.version`, WebSocket subprotocol, or normative protocol behavior.
- Changing client-side crypto profile interpretation in a way that affects interoperability (even if the `.proto` is unchanged).
- Introducing dual-stack or deprecation timelines for an existing wire version.

Do **not** merge `.proto` or behavioral changes without a completed PDR in this folder (except emergency fixes — then backfill the PDR immediately).

## How to add a record

1. Copy [`0000-template.md`](0000-template.md) to the next sequential file, e.g. `0001-short-title.md`.
2. Fill every section. Use `status: proposed` until maintainers accept; then `accepted` or `rejected`.
3. Link the PDR from release notes or `docs/protocol-migration-plan.md` when the change ships.
4. Run the **test matrix** described in the PDR before tagging any release that changes wire or crypto interpretation.

## Numbering

| File | Purpose |
|------|---------|
| `0000-template.md` | Canonical template — do not use as a live decision |
| `0001-…`, `0002-…` | Accepted or proposed decisions in chronological order |

Titles should be short kebab-case slugs in the filename (e.g. `0003-welcome-capability-negotiation.md`).

## Related documents

- [`../protocol-migration-plan.md`](../protocol-migration-plan.md) — versioning principles, compatibility matrix, rollback strategy
- [`../protobuf-contract.md`](../protobuf-contract.md) — field-level contract and compatibility rules
- [`../threat-model.md`](../threat-model.md) — privacy and adversary baseline for impact analysis
- [`../ghost-bunker-protocol-v0.1.md`](../ghost-bunker-protocol-v0.1.md) — current normative v0.1 spec
- [`../releases/v1.0-protocol-cryptographic-review.md`](../releases/v1.0-protocol-cryptographic-review.md) — audit milestone (documentation only; not a security tag)

## Audit and security claims

A PDR does **not** by itself imply cryptographic security or an audited release. If `audit requirement` is yes, follow the milestone in `docs/releases/v1.0-protocol-cryptographic-review.md` before any public security claim.
