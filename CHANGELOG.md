# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0-alpha] - 2026-05-27

### Added

- Docker image (`Dockerfile`) and `docker-compose.yml` for staging deployments.
- Reverse proxy deployment guide (`docs/reverse-proxy-deployment.md`).
- `application-prod.yml` profile with graceful shutdown, subprotocol enforcement, and split actuator port.
- Configurable WebSocket allowed origins and optional `Sec-WebSocket-Protocol` enforcement.
- Graceful JVM shutdown broadcasting `GOODBYE` with `SERVER_SHUTDOWN`.
- Identity-free Micrometer metrics (`ghostbunker.*`) and Prometheus scrape endpoint.
- Protobuf load simulator (`GhostBunkerLoadSimulator`) for synthetic staging tests.

### Changed

- `HeartbeatService` uses canonical goodbye messages on the wire.
- Project version `0.3.0-SNAPSHOT`.

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

