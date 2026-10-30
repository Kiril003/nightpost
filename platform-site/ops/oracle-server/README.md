# Server on the Oracle VM

Moves: the licence/relay server (`platform-site/server`, FastAPI) from Fly app `phantom-license` to `/srv/nightpost-api` on the Oracle VM, behind the host nginx at `api.phantom-os.dev`. Stays: coturn (TURN), relay v1 on :8787 (try.phantom-os.dev), signaling, demo node, minio, Supabase Postgres, R2, Netlify. Runbook: `MIGRATE.md`.

Files: `compose.yaml` (one container, 768 MB cap, 1 CPU, read-only rootfs, rotated logs), `server.env.example`, `api.phantom-os.dev.nginx` (host nginx site; Caddy not used because nginx already owns 80/443), `export-data.py` (Fly export and nightly backup), `dump-env.py` (runs on Fly, writes `server.env`), `ws-smoke.py` (live-channel check), `fly-proxy/` (Fly app becomes a forwarder). The faucet wallet is described in `../faucet.md`.

Risks: Oracle reclaims idle Always Free instances (CPU p95, network and memory all under 20 % for 7 days); `np-keepalive` holds the RAM criterion, the owner was advised to switch the tenancy to Pay-As-You-Go, restated here. Single VM, single container, no failover. VM has no IPv6, so Supabase needs the IPv4 Transaction pooler (port 6543), not the direct host. Transitive Python dependencies are unpinned; all 56 resolve to aarch64 cp312 wheels today (checked), but a rebuild can drift; run the WebSocket smoke check after every build.

## For the architect

1. Licences, accounts and directory are in Supabase and do not move; the Fly volume holds the signing key, policy, points and venues.
2. API goes to `api.phantom-os.dev` (host nginx, Let's Encrypt, no access log, Fly-Client-IP overwritten).
3. Old URL is kept alive by a Fly forwarder, not a 301 (POST and WebSocket break on redirects). About 2 USD a month, so Fly is not fully dropped until old builds fade.
4. The forwarder proves itself to nginx with a shared secret so per-IP rate limits keep working.
5. RAM: server 768 MB cap (about 0.3 typical), VM has 7.9 GB available with the keepalive yielding.
6. Disk: 45 GB free, need about 2 GB, no block volume possible within the free tier.
7. The container refuses to start without the signing key.
8. The relay store is born empty; phones re-deposit via `store_since_ms`.
9. Done on `cloud/server-1510`: site CSP `connect-src` for the new host (ships with the next site deploy), faucet server, guards in `server/tests/test_oracle_package.py`, `compose.yaml` and the env pipeline checked locally with `docker compose config`. Not done: app and PC base URL change, ARM build on the VM.
10. Owner commands: `_briefs/server-1510-owner.md` (path B), in rounds of at most three actions.
