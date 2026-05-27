# Reverse proxy deployment (staging)

This guide describes how to run the Ghost Bunker **reference server** behind a TLS-terminating
reverse proxy for **staging**. It does not cover full production hardening (WAF, multi-region,
clustering, etc.).

The server remains **single-node and in-memory**. Do not run multiple instances behind the same
load balancer without accepting that rooms will partition across nodes.

---

## Architecture

```
Browser (wss) ──► Reverse proxy (TLS, no access logs) ──► JVM :8080 /ghost-bunker
                                                      └──► JVM :8081 /actuator (internal only)
```

- **Public traffic**: WebSocket upgrade to `/ghost-bunker` on port `8080` (HTTP from proxy to JVM).
- **Operations**: Prometheus scrape and health checks on port `8081` — **not** exposed on the public internet.
- **Profile**: `SPRING_PROFILES_ACTIVE=prod` loads `application-prod.yml`.

---

## Privacy-Max proxy requirements

The proxy must **not** log:

- Client IP addresses (disable access logs or use a format with no `%a` / `$remote_addr`).
- Request or response bodies (no WebSocket frame logging).
- `Sec-WebSocket-Protocol`, `Cookie`, or query strings that might carry secrets.

Verify after deployment with `docs/privacy-max-profile-v0.1.md` and repeat `PrivacyLogAuditIT`-style
checks against collected proxy logs.

---

## Server configuration (behind proxy)

Set trusted browser origins and subprotocol enforcement via environment variables:

```bash
export SPRING_PROFILES_ACTIVE=prod
export GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS=https://staging.example.com
```

`application-prod.yml` defaults `allowed-origins` to an empty list — connections are rejected until
you set at least one HTTPS origin.

WebSocket subprotocol `ghost-bunker.v0.1` is **required** in the prod profile.

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
        # Do not forward X-Forwarded-For to application logs (server does not log IP anyway).
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

Reload: `nginx -t && systemctl reload nginx`

Clients connect to: `wss://staging.example.com/ghost-bunker`

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

---

## Docker Compose (server only)

From the repository root:

```bash
docker compose up --build
```

Set `GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS` in `docker-compose.yml` before exposing staging traffic.

Scrape metrics internally:

```bash
curl -s http://127.0.0.1:8081/actuator/prometheus
```

---

## Rolling restart

1. Send `SIGTERM` to the JVM (Kubernetes `preStop`, Docker stop, systemd).
2. `GracefulShutdownService` broadcasts `GOODBYE` with reason `SERVER_SHUTDOWN` and message `server shutdown`.
3. After `ghostbunker.shutdown.grace-period-ms` (default 2000 ms), the process exits.

Clients should treat `SERVER_SHUTDOWN` as a signal to reconnect after the deploy completes.

---

## Checklist

- [ ] TLS 1.2+ on the proxy; `ws://` only for local development
- [ ] Proxy access logs disabled or verified free of IP/URI/body
- [ ] `GHOSTBUNKER_WEBSOCKET_ALLOWED_ORIGINS` set to staging web origins
- [ ] Actuator port `8081` reachable only from the monitoring network
- [ ] `mvn clean verify` green on the release tag
- [ ] Load simulator smoke test through the proxy path (see `docs/manual-testing.md`)
