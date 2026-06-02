# Load testing (non-production)

This repository includes a **non-production** load simulator that speaks the **real** Ghost Bunker Protocol v0.1 over **WebSocket binary frames** using the `.proto` contract.

It is intended for:

- Basic throughput and fan-out characterization
- Regression detection (handshake, routing, backpressure, rate limits, heartbeat)
- Capacity experiments in staging-like environments

It is **not** intended for:

- Benchmarking cryptography (the server never decrypts; the simulator uses synthetic ciphertext)
- Privacy/security evaluation beyond the constraints below

---

## Privacy constraints (simulator)

The simulator prints **aggregate results only** and must not emit identifiers.

It does **not** log:

- IP / remote address
- full `session_id` or `user_id`
- ciphertext / plaintext
- room keys (none are used)
- per-room or per-client identifiers in results

The simulator does **not** persist results.

---

## Running the server locally (Docker)

From the repository root:

```bash
docker compose up --build
```

Default endpoints:

- WebSocket: `ws://localhost:8080/ghost-bunker` (requires subprotocol `ghost-bunker.v0.1` in `prod`)
- Metrics/health: `http://localhost:8081/actuator/health`

---

## Load simulator

Location: `tools/load-simulator/` (standalone Maven project; not part of the server build).

### Build

```bash
mvn -f tools/load-simulator/pom.xml -q package
```

### Run

```bash
mvn -f tools/load-simulator/pom.xml -q exec:java -Dexec.args="--url ws://localhost:8080/ghost-bunker --clients 20 --rooms 5 --messages-per-client 10"
```

Options:

- `--url`: WebSocket URL (default `ws://localhost:8080/ghost-bunker`)
- `--clients`: number of concurrent clients (default `20`)
- `--rooms`: number of rooms to spread clients across (default `5`)
- `--messages-per-client`: messages each client sends after joining (default `10`)
- `--ciphertext-bytes`: synthetic ciphertext size (default `128`, must be <= server limit)
- `--connect-timeout-ms`: WebSocket connect timeout (default `5000`)
- `--run-timeout-ms`: per-client watchdog timeout (default `60000`)

### Protocol behavior

Each simulated client:

1. Connects with WebSocket subprotocol `ghost-bunker.v0.1`
2. Sends `HELLO`
3. Waits briefly for `WELCOME` (best-effort)
4. Sends `JOIN_ROOM`
5. Sends `SEND_ENCRYPTED_MESSAGE` frames with:
   - random 12-byte nonce
   - synthetic random ciphertext bytes
   - `cipher_suite = PBKDF2_HMAC_SHA256_AES_256_GCM` (wire label)
6. Responds to server `PING` frames with `PONG` (echoing nonce)
7. Prints aggregate counters (sent/received/errors/goodbyes), without identifiers

