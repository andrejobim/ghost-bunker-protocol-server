# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0-alpha] - 2026-05-27

### Added

- Initial Ghost Bunker Protocol reference server.
- WebSocket transport with a Protobuf envelope contract.
- Single-node, in-memory sessions and rooms (no persistence).
- End-to-end encrypted (E2EE) ciphertext routing (relay-only, no decryption on server).
- Privacy-Max audit documentation and operational profile guidance.
- Canonical `ErrorMessage.message` mapping driven by `ErrorCode`.
- Manual clients for interactive testing.
- Automated unit + integration test suite (`mvn clean verify`).

