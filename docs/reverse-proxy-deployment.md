# Reverse proxy deployment (staging)

This guide describes how to run the Ghost Bunker **reference server** behind a TLS-terminating
reverse proxy for **staging**. It does not cover full production hardening (WAF, multi-region,
clustering, etc.).

The server remains **single-node and in-memory**. Do not run multiple instances behind the same
load balancer without accepting that rooms will partition across nodes.

---

## Quick reference

| Item | Value |
|------|--------|
| Application port | **8080** — WebSocket and (in dev) actuator on the same connector |
| Management port (`prod` profile) | **8081** — `/actuator/health`, `/actuator/prometheus` only |
| WebSocket path | **`/ghost-bunker`** |
| Required subprotocol (`prod`) | **`ghost-bunker.v0.1`** (`Sec-WebSocket-Protocol`) |
| Local dev URL | `ws://localhost:8080/ghost-bunker` |
| Staging / production URL | `wss://<host>/ghost-bunker` |
| Spring profile for staging | `SPRING_PROFILES_ACTIVE=prod` |
| Allowed origins env | `GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS` |

**TLS:** Terminate TLS at the reverse proxy or edge (CDN, load balancer). The JVM speaks plain
HTTP to the proxy on port `8080`; clients never connect with `ws://` except on localhost during
development.

**WebSocket upgrade:** The proxy must forward the RFC 6455 upgrade (`Connection: Upgrade`,
`Upgrade: websocket`) end-to-end. A proxy that only handles HTTP/1.1 request/response without
upgrade support will break the protocol.

---

## Connection URLs

### Local development (no proxy)

Run with the default profile (`mvn spring-boot:run`):

```text
ws://localhost:8080/ghost-bunker
```

- Subprotocol enforcement is **off** (`ghostbunker.websocket.required-subprotocol` is blank).
- Allowed origins default to `*` in `application.yml`.
- Actuator endpoints are on the **same port** as the app: `http://localhost:8080/actuator/health`.

Use `ws://` only on loopback. Do not expose unencrypted WebSocket to the public internet.

### Staging / production (behind reverse proxy)

Clients connect to the **public hostname** over TLS; the proxy forwards to the JVM:

```text
wss://<host>/ghost-bunker
```

Example: `wss://staging.example.com/ghost-bunker`

Activate the `prod` profile and configure trusted origins (see below). Clients must offer
subprotocol `ghost-bunker.v0.1` in the handshake.

---

## Ports

```
Browser (wss) ──► Reverse proxy (TLS terminated here) ──► JVM :8080  /ghost-bunker
                                                    └──► JVM :8081  /actuator/*  (internal only)
```

| Port | Profile | Purpose |
|------|---------|---------|
| **8080** | all | Embedded Tomcat: WebSocket at `/ghost-bunker`. In `prod`, this is the only port the public proxy should reach. |
| **8081** | `prod` only | Separate management connector (`management.server.port` in `application-prod.yml`). Health and Prometheus scrape. **Do not** publish on the public internet. |

In the default (dev) profile, management shares port **8080** (`/actuator/...`).

Internal scrape example (prod, from the monitoring network):

```bash
curl -fsS http://127.0.0.1:8081/actuator/health
curl -fsS http://127.0.0.1:8081/actuator/prometheus
```

---

## WebSocket contract

| Requirement | Detail |
|-------------|--------|
| Path | `/ghost-bunker` (exact; no trailing slash required by the server) |
| Subprotocol (`prod`) | Client must send `Sec-WebSocket-Protocol: ghost-bunker.v0.1`. Handshakes without it are rejected when `required-subprotocol` is set in `application-prod.yml`. |
| Subprotocol (dev) | Optional; not enforced unless you set `ghostbunker.websocket.required-subprotocol` |
| Binary frames | Protobuf `GhostEnvelope` payloads after handshake |
| Proxy | Must support **WebSocket upgrade** and long-lived connections (see timeouts in examples below) |

The wire protocol name inside envelopes is `ghost-bunker` (field `protocol` in `HELLO`). That is
**not** the same as the WebSocket subprotocol header; both are required in staging (`prod`).

---

## TLS termination

- **Clients → proxy:** HTTPS / `wss://` (TLS 1.2+ recommended).
- **Proxy → JVM:** plain HTTP to `127.0.0.1:8080` (or the container/service address on port 8080).
- The reference server does **not** configure TLS in `application.yml`. Termination at the proxy
  or edge is the supported deployment model.

---

## Allowed origins

Browsers send an `Origin` header on the WebSocket handshake. In `prod`, the server rejects
handshakes unless the origin is listed in `ghostbunker.websocket.allowed-origins`.

`application-prod.yml` defaults to an **empty** list — every connection is rejected until you
configure at least one trusted origin.

### Environment variable (recommended)

Comma-separated HTTPS origins (no trailing path):

```bash
export SPRING_PROFILES_ACTIVE=prod
export GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS=https://staging.example.com,https://app.staging.example.com
```

### YAML (`application-prod.yml` or override)

```yaml
ghostbunker:
  websocket:
    allowed-origins:
      - https://staging.example.com
    required-subprotocol: ghost-bunker.v0.1
```

Use explicit `https://` origins that match the page loading your web client. Wildcard `*` is
appropriate for **local development only** (`application.yml` default).

---

## Access logs and Privacy-Max

Logging policy applies at **three layers**: reverse proxy, JVM, and (optionally) OS. Staging must
treat the proxy as part of the privacy boundary.

### Reverse proxy

The proxy **must**:

- Support WebSocket upgrade (see above).
- Use TLS on the public listener.
- **Disable access logs**, or use a format that cannot record:
  - Client IP (`$remote_addr`, `%a`, `X-Forwarded-For` in log lines)
  - Request URI, query string, or path details that could correlate users
  - Request/response bodies or WebSocket frame payloads
  - `Sec-WebSocket-Protocol`, `Cookie`, `Authorization`, or other sensitive headers

The proxy **must not** log ciphertext, Protobuf payloads, nicknames, `session_id`, or `user_id`.
Those exist only inside binary WebSocket frames after upgrade; frame logging must remain off.

### Application (JVM)

Already enforced in `application.yml`:

- `server.tomcat.accesslog.enabled: false` — no Tomcat access log lines (no per-request IP/URI).
- Application logs use `SanitizedProtocolLogger` only; see `docs/privacy-max-profile-v0.1.md`.

Verify after deployment with `docs/privacy-max-profile-v0.1.md` and repeat
`PrivacyLogAuditIT`-style checks against **collected proxy and application logs**.

---

## Server configuration (behind proxy)

```bash
export SPRING_PROFILES_ACTIVE=prod
export GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS=https://staging.example.com
```

Optional shutdown tuning:

```bash
export GHOSTBUNKER_SHUTDOWN_GRACE_PERIOD_MS=2000
```

---

## nginx example

```nginx
# /etc/nginx/sites-available/ghost-bunker-staging.conf
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 443 ssl http2;
    server_name staging.example.com;

    ssl_certificate     /etc/ssl/certs/staging.example.com.pem;
    ssl_certificate_key /etc/ssl/private/staging.example.com.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    # Privacy-Max: no access_log in staging
    access_log off;

    location /ghost-bunker {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        # Do not forward X-Forwarded-For into logs you might enable elsewhere.
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    # Do not expose :8081 on this server block. Scrape metrics from an internal network only.
}
```

Reload: `nginx -t && systemctl reload nginx`

Clients: `wss://staging.example.com/ghost-bunker` with subprotocol `ghost-bunker.v0.1`.

---

## Caddy example

```caddy
staging.example.com {
    access_log off

    reverse_proxy /ghost-bunker 127.0.0.1:8080 {
        header_up Host {host}
        header_up Connection {>Connection}
        header_up Upgrade {>Upgrade}
    }
}
```

Clients: `wss://staging.example.com/ghost-bunker` with subprotocol `ghost-bunker.v0.1`.

---

## Docker Compose (server only)

From the repository root:

```bash
docker compose up --build
```

The compose file activates `prod`, maps **8080** and **8081**, and sets dev-friendly HTTP
origins for a local Vite client. Before real staging traffic, replace
`GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS` with your HTTPS web origins and put TLS in front of the
published ports (or stop publishing `8080`/`8081` directly and proxy only from an internal network).

Scrape metrics on the management port (from the host or monitoring network):

```bash
curl -fsS http://127.0.0.1:8081/actuator/prometheus
```

---

## Rolling restart

1. Send `SIGTERM` to the JVM (Kubernetes `preStop`, Docker stop, systemd).
2. `GracefulShutdownService` broadcasts `GOODBYE` with reason `SERVER_SHUTDOWN` and message
   `server shutdown`.
3. After `ghostbunker.shutdown.grace-period-ms` (default 2000 ms), the process exits.

Clients should treat `SERVER_SHUTDOWN` as a signal to reconnect after the deploy completes.

---

## Checklist

- [ ] TLS 1.2+ on the proxy; public clients use `wss://<host>/ghost-bunker` only
- [ ] Proxy supports WebSocket upgrade; long timeouts for idle chat connections
- [ ] Proxy access logs disabled or verified free of IP, URI, body, and sensitive headers
- [ ] `SPRING_PROFILES_ACTIVE=prod` on staging JVMs
- [ ] `GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS` set to staging HTTPS web origins
- [ ] Clients send `Sec-WebSocket-Protocol: ghost-bunker.v0.1`
- [ ] Actuator port **8081** reachable only from the monitoring network (not via public DNS)
- [ ] Application port **8080** reachable only from the proxy (not directly from the internet)
- [ ] `mvn clean verify` green on the release tag
- [ ] Load simulator or reference client smoke test through the proxy path (see `docs/manual-testing.md`)
