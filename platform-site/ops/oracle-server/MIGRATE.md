# Move the server from Fly to the Oracle VM

Owner runbook. The code to deploy is this branch, `cloud/server-1510` (Vertex brain, Oracle package, K6 faucet, site fixes). Run every command from `~/phantom_ai/wt-server-1510/platform-site` on the laptop unless a line starts with `ssh`; `git branch --show-current` must print `cloud/server-1510`. Nothing below was run end to end by the agent that wrote it: only the read-only VM checks and the local script tests.

Facts, read from the VM 15.10 (read-only): aarch64, 2 cores, 11.9 GB RAM, `/` 193 GB with 45 GB free (77 %), Docker 29.7 + Compose 5.5 + certbot installed, host nginx 1.24 owns 80/443 (`try.phantom-os.dev`), coturn active, relay v1 on :8787, port 8480 free, region eu-zurich-1, user `ubuntu` has uid 1001 and passwordless sudo, ssh alias `phantom-oracle` works with the key, **no IPv6**, public IP 140.238.221.7, `/srv/nightpost-api` does not exist yet, `api.phantom-os.dev` has no DNS record yet.

What lives where today: licences, accounts and the directory are in **Supabase Postgres** (`DATABASE_URL` Fly secret), so they do not move. The Fly volume `phantom_license_data` (`/data`) holds the **licence signing key**, `keys/policy.json`, `points.db`, `venues.db`. The relay store and truth DB (`/relay`) are on the Fly machine disk and are not migrated: phones re-deposit unfetched letters when they see the new `store_since_ms`.

Never start the container before the key is in place: a missing key would mint a new one and void every issued certificate. `compose.yaml` refuses to start without `/data/keys/signing.pem`.

Secrets rule for every step: a value moves only through a pipe between two processes, from a file, or from a no-echo prompt. It never appears in a command line, in a shell variable that outlives its step, or on screen. Listings below show names or counts only.

## 1. Prepare the VM (no user impact)

```bash
ssh phantom-oracle 'rm -rf ~/.cache/pip && sudo install -d -o ubuntu -g ubuntu /srv/nightpost-api/{src,data,relay,secrets,backups} && chmod 700 /srv/nightpost-api/data /srv/nightpost-api/secrets /srv/nightpost-api/backups && echo "NP_UID=$(id -u)" > /srv/nightpost-api/.env && df -h / | tail -1'
rsync -az --delete --exclude .env --exclude '.env.*' --exclude .venv --exclude __pycache__ --exclude tests --exclude '*.db' --exclude keys server/ phantom-oracle:/srv/nightpost-api/src/
scp ops/oracle-server/compose.yaml ops/oracle-server/api.phantom-os.dev.nginx ops/oracle-server/export-data.py phantom-oracle:/srv/nightpost-api/
```

`backups/` will hold the signing key, hence 700. `NP_UID` is the uid compose runs the container as; writing it from `id -u` on the VM keeps the ownership of `data/` and `relay/` matching.

Disk: no block volume needed and none is possible for free (the boot volume already takes 193 of the 200 GB allowance). The server needs about 2 GB (image 0.5, data under 0.1, relay ceiling 0.5, logs 30 MB). The `rm` above frees 3.7 GB. Not required, owner's call: `~/apk-integration-merge2-12sep` (2.5 GB, 12 Sep). The forge cycle keeps its own 15 GB free floor.

## 2. Export from Fly (read-only for Fly)

```bash
install -d -m 700 ~/nightpost-backup
flyctl ssh sftp put ops/oracle-server/export-data.py /tmp/export-data.py -a phantom-license
flyctl ssh console -a phantom-license -C "python /tmp/export-data.py /tmp/nightpost-data.tgz"
flyctl ssh sftp get /tmp/nightpost-data.tgz ~/nightpost-backup/nightpost-data.tgz -a phantom-license && chmod 600 ~/nightpost-backup/nightpost-data.tgz
L=$(sha256sum ~/nightpost-backup/nightpost-data.tgz | cut -d' ' -f1); R=$(flyctl ssh console -q -a phantom-license -C "sha256sum /tmp/nightpost-data.tgz" | cut -d' ' -f1)
[ "$L" = "$R" ] && flyctl ssh console -a phantom-license -C "rm -f /tmp/nightpost-data.tgz /tmp/export-data.py" && echo "hashes match, machine copy removed" || echo "MISMATCH: repeat the export"
```

The tgz holds the signing key, so it is removed from the Fly machine's `/tmp` as soon as the local copy is proven identical, and it never goes inside a repo. Keep one encrypted offline copy of `~/nightpost-backup/`. Do a rehearsal export first; the final export is in step 7.

## 3. Environment

`server.env.example` lists every variable the server reads. Build `/srv/nightpost-api/server.env` on the VM straight from what is set on Fly. Values are written as `NAME='value'`: compose expands `$` in unquoted and double-quoted values, and single quotes keep them literal.

```bash
flyctl ssh sftp put ops/oracle-server/dump-env.py /tmp/dump-env.py -a phantom-license
NAMES=$(grep -oE '^[A-Z][A-Z0-9_]*=' ops/oracle-server/server.env.example | tr -d = | grep -vxE 'GOOGLE_SA_JSON|MAIL_DEV_SINK|DATABASE_URL' | tr '\n' ' ')
flyctl ssh console -q -a phantom-license -C "python /tmp/dump-env.py $NAMES" | tr -d '\r' | grep -E "^[A-Z][A-Z0-9_]*='.*'$" | ssh phantom-oracle 'umask 077; cat > /srv/nightpost-api/server.env'
flyctl ssh console -a phantom-license -C "rm -f /tmp/dump-env.py"
ssh phantom-oracle "cut -d= -f1 /srv/nightpost-api/server.env | sort | tr '\n' ' '; echo; stat -c '%a' /srv/nightpost-api/server.env"
```

The last line lists names only, then the file mode (600). A line `set by hand, cannot be single-quoted: …` from the dump means that value has a quote or a line break in it: add that one yourself. `MAIL_DEV_SINK` stays out on purpose: it writes mail to a file and reports success.

**Database.** The `DATABASE_URL` on Fly is the direct Supabase host on port 5432, which is IPv6-only. The VM has no IPv6, so `Store()` fails at import and the container restarts in a loop. Use the IPv4 pooler: Supabase dashboard, Connect, **Transaction pooler**: host `aws-…pooler.supabase.com`, port 6543, user `postgres.<project-ref>`. `db.py` already turns off prepared statements for it. Same database password as today; do not reset it, the string live on Fly would stop working.

```bash
printf 'Pooler URI with the real password: '; stty -echo; read -r U; stty echo; echo
printf "DATABASE_URL='%s'\n" "$U" | ssh phantom-oracle 'umask 077; cat >> /srv/nightpost-api/server.env'; unset U
ssh phantom-oracle "grep -c '^DATABASE_URL=.*pooler\.supabase\.com:6543/' /srv/nightpost-api/server.env"
```

The last line must print `1`; `0` means the string is not the pooler one.

**Vertex.** The service-account key becomes a file on the VM, copied from the key file on the laptop (the Fly secret `GOOGLE_SA_JSON` may be empty, so it is not the source). This folds in `_briefs/brain-vertex-deploy.md`:

```bash
J=/home/kyrylo/Завантажене/project-de0229bf-aea5-4baa-964-abf44aa64961.json
test -s "$J" && scp "$J" phantom-oracle:/srv/nightpost-api/secrets/vertex-sa.json
ssh phantom-oracle "chmod 600 /srv/nightpost-api/secrets/vertex-sa.json; wc -c < /srv/nightpost-api/secrets/vertex-sa.json; printf \"VERTEX_PROJECT='project-de0229bf-aea5-4baa-964'\nVERTEX_LOCATION='global'\nGOOGLE_APPLICATION_CREDENTIALS='/secrets/vertex-sa.json'\n\" >> /srv/nightpost-api/server.env"
```

The byte count should be over 2000. If the key file has moved, change only `J=`. The container's log shows `brain: upstream=vertex location=global token ok` after the first assistant question; no such line means the server is still on the Gemini key.

**Faucet.** `SOLANA_FAUCET_KEY` and `SOLANA_FAUCET_RPC_URL` are already in the dump if they are set on Fly. If not, make and fund the devnet wallet as described in `../faucet.md`, then:

```bash
{ printf "SOLANA_FAUCET_KEY='%s'\n" "$(cat ~/nightpost-secrets/faucet-devnet.json)"; echo "SOLANA_FAUCET_RPC_URL='https://api.devnet.solana.com'"; } | ssh phantom-oracle 'umask 077; cat >> /srv/nightpost-api/server.env'
```

Keep `TURN_URLS` and `TURN_SECRET` as they are: coturn stays on this VM.

## 4. Put the data in place

```bash
scp ~/nightpost-backup/nightpost-data.tgz phantom-oracle:/srv/nightpost-api/backups/
ssh phantom-oracle 'cd /srv/nightpost-api && tar xzf backups/nightpost-data.tgz -C data && ls data/keys'
```

## 5. nginx and certificate

Cloudflare DNS for phantom-os.dev: add `A api -> 140.238.221.7`, DNS only (grey cloud), TTL 60. The edge secret is made on the VM and stays there; nothing in this step prints it.

```bash
ssh phantom-oracle 'umask 077; [ -s /srv/nightpost-api/secrets/edge ] || openssl rand -hex 24 > /srv/nightpost-api/secrets/edge'
ssh phantom-oracle 'printf "\"%s\" \$http_fly_client_ip;\n" "$(cat /srv/nightpost-api/secrets/edge)" | sudo tee /etc/nginx/snippets/np-edge.map >/dev/null && sudo chmod 640 /etc/nginx/snippets/np-edge.map && sudo install -m 644 /srv/nightpost-api/api.phantom-os.dev.nginx /etc/nginx/sites-available/api.phantom-os.dev && sudo ln -sfn /etc/nginx/sites-available/api.phantom-os.dev /etc/nginx/sites-enabled/api.phantom-os.dev && sudo nginx -t && sudo systemctl reload nginx && sudo certbot --nginx -d api.phantom-os.dev --non-interactive --agree-tos -m hello@phantom-os.dev --redirect'
```

The edge secret makes the map key longer than nginx's default 64-byte bucket (`could not build map_hash`), so before the block above run once: `ssh phantom-oracle 'echo "map_hash_bucket_size 128;" | sudo tee /etc/nginx/conf.d/np-map-hash.conf'`.

## 6. Start and smoke-test (rehearsal, Fly still serving)

```bash
ssh phantom-oracle 'cd /srv/nightpost-api && docker compose up -d --build && sleep 25 && docker compose ps && docker compose logs server 2>&1 | grep -c Traceback; free -m | head -2'
curl -s https://api.phantom-os.dev/healthz        # public_key must equal: curl -s https://phantom-license.fly.dev/healthz
curl -s https://api.phantom-os.dev/api/brain/status
curl -s https://api.phantom-os.dev/relay/v2/health
curl -s -X POST https://api.phantom-os.dev/api/validate -H 'content-type: application/json' -d '{"license_id":"x","cert_serial":"x","device_fingerprint":"'$(printf 'a%.0s' {1..64})'"}'   # 200 with a signed statement, or 4xx: not 5xx
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.phantom-os.dev/api/solana/faucet -H 'content-type: application/json' -d '{"wallet":"1111111111111111111111111111111111","device":"smoke-test-0001"}'   # 400 = faucet key loaded, 503 = no key
ssh phantom-oracle 'cd /srv/nightpost-api && docker compose exec -T server python -' < ops/oracle-server/ws-smoke.py   # {"t": "pong"}
```

The `Traceback` count must be `0`. A container that keeps restarting, with a connection error from `Store` in `docker compose logs server`, means step 3 left a wrong `DATABASE_URL`.

Also check: a request with `Fly-Client-IP: 6.6.6.6` from outside must not change your rate-limit key (nginx overwrites it); `ssh phantom-oracle 'systemctl is-active coturn'` stays `active`; DB latency from the container is acceptable. Data written during this rehearsal (points, venues) is thrown away by step 7.

## 7. Cut over (users notice a few seconds)

Fresh data goes in first, then Fly becomes a forwarder. Until the forwarder is up the site and the apps keep talking to Fly, so venue and points edits are never written to two places.

```bash
ssh phantom-oracle 'cd /srv/nightpost-api && docker compose down && find data -name "*.db*" -delete'
```

Repeat step 2 (export, hash check) and step 4 (copy and unpack), then:

```bash
ssh phantom-oracle 'cd /srv/nightpost-api && docker compose up -d && sleep 20 && docker compose ps'
ssh phantom-oracle 'printf "EDGE_SECRET=%s\n" "$(cat /srv/nightpost-api/secrets/edge)"' | flyctl secrets import -a phantom-license --stage
(cd ops/oracle-server/fly-proxy && flyctl deploy -a phantom-license --depot=false --ha=false)
curl -s https://phantom-license.fly.dev/healthz    # now answered by the VM
```

The forwarder directory is the build context, so its own `Dockerfile` and `default.conf.template` are used. The few seconds between the final export and the forwarder going live are the only window where points or venue edits can be lost. Keep the forwarder at least 60 days and until old builds are gone (about 2 USD a month). Keep the Fly volume for 30 days (rollback source), then `flyctl volumes delete` and drop the Fly secrets.

## 8. Do not 301 the old host

Phones and PCs in the field have `phantom-license.fly.dev` baked in. A redirect breaks POST (Ktor does not follow it) and WebSocket. Use the forwarder in step 7; DNS cannot help because fly.dev is Fly's name.

## 9. Site and builds (after step 7)

1. Site: this branch already allows `https://api.phantom-os.dev` in `connect-src` (`netlify.toml` and `scripts/phantom-os-headers.conf`, pinned by `server/tests/test_site_csp.py`). That deploy must be live first; then set Netlify env `VITE_LICENSE_SERVER=https://api.phantom-os.dev` and deploy again. Without the CSP host the browser blocks the cabinet.
2. App and PC builds: next release sets `phantom.licenseHost=https://api.phantom-os.dev` in `local.properties`; also `DEBUG_RELAY_HOST` in `phantom-companion/app/build.gradle.kts`, `update.rs` and `tauri.conf.json` CSP, `phantom-os` backend defaults (`config.py`, `activator.py`), `peerDirectory.ts`.

## 10. Backups (on the VM)

```bash
ssh phantom-oracle '(crontab -l 2>/dev/null | grep -v export-data.py; echo "17 3 * * * umask 077; cd /srv/nightpost-api && docker compose exec -T server python - < export-data.py > backups/.tmp.tgz && mv backups/.tmp.tgz backups/data-\$(date +\%F).tgz && find backups -name \"data-*.tgz\" -mtime +14 -delete") | crontab -'
```

`/srv/nightpost-api/relay` must never be backed up (the "seven days and gone" promise). Copy a backup off the VM weekly into `~/nightpost-backup/` (TODO: R2 sync): `scp phantom-oracle:/srv/nightpost-api/backups/data-$(date +%F).tgz ~/nightpost-backup/`.

## Rollback

Before step 7: nothing changed for users; `docker compose down` on the VM. After step 7: `flyctl releases -a phantom-license --image` to find the old image, then `flyctl deploy -a phantom-license -c server/fly.toml --image <old-image>`, and flip the site env back. Points and venue edits made on the VM since the cutover are lost unless exported first: `ssh phantom-oracle 'cd /srv/nightpost-api && docker compose exec -T server python - < export-data.py' > ~/nightpost-backup/back.tgz`, then put it on Fly with `sftp put` and untar into `/data`.
