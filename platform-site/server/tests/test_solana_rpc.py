"""Проксі Solana JSON-RPC: що він пропускає, чого не пропустить ніколи і що не вийде назовні.

Мережа підмінена httpx.MockTransport: апстрім — це функція, яка бачить рівно те, що
сервер послав би справжньому вузлу. Кожна відмова звіряє ПРИЧИНУ, а не лише код.
"""
import json
import logging
import re
import sys
from pathlib import Path

import httpx
import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage",
           "solana_pay", "sponsor", "solana_rpc")
SECRET = "SECRETk3y9d0b1e2f3a4"
DEVNET = f"https://devnet.upstream.test/?api-key={SECRET}"
MAINNET = f"https://mainnet.upstream.test/v2/{SECRET}"
ADDRESS = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
SOLANA_RPC_KT = SERVER_DIR.parents[1] / "phantom-companion/core-solana/src/main/java/local/phantom/companion/core/solana"


class Upstream:
    def __init__(self):
        self.seen: list[httpx.Request] = []
        self.reply = lambda req: httpx.Response(
            200, json={"jsonrpc": "2.0", "id": json.loads(req.content)["id"] if req.content.startswith(b"{") else None,
                       "result": {"context": {"slot": 7}, "value": 42}},
        )

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.seen.append(request)
        return self.reply(request)

    def bodies(self) -> list:
        return [json.loads(r.content) for r in self.seen]


class World:
    def __init__(self, client, rpc, net):
        self.client, self.rpc, self.net = client, rpc, net

    def call(self, method, params=None, cluster="devnet", rid=1, ip="203.0.113.7"):
        body = {"jsonrpc": "2.0", "id": rid, "method": method, "params": params if params is not None else []}
        return self.client.post(f"/api/solana/rpc/{cluster}", json=body, headers={"fly-client-ip": ip})


def _behind_fly_proxy(app):
    """Сусід — довірений проксі, тож Fly-Client-IP із запиту стає ключем ліміту, як на Fly."""
    async def wrapped(scope, receive, send):
        if scope["type"] == "http":
            scope = dict(scope, client=("127.0.0.1", 50000))
        await app(scope, receive, send)
    return wrapped


def _boot(tmp_path, monkeypatch, devnet=DEVNET, mainnet=MAINNET):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "rpc.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    for name, value in (("SOLANA_RPC_URL_DEVNET", devnet), ("SOLANA_RPC_URL_MAINNET", mainnet)):
        if value:
            monkeypatch.setenv(name, value)
        else:
            monkeypatch.delenv(name, raising=False)
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import main as server
    import solana_rpc
    from fastapi.testclient import TestClient

    net = Upstream()
    solana_rpc.transport = httpx.MockTransport(net)
    solana_rpc.limiter = solana_rpc.Limiter()
    return World(TestClient(_behind_fly_proxy(server.app)), solana_rpc, net)


@pytest.fixture()
def w(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def reason(res):
    return res.json().get("detail")


def test_allowed_read_goes_to_our_upstream_with_our_key_and_comes_back_verbatim(w):
    res = w.call("getBalance", [ADDRESS, {"commitment": "confirmed"}])
    assert res.status_code == 200
    assert res.json()["result"]["value"] == 42
    [sent] = w.net.seen
    assert str(sent.url) == DEVNET
    assert w.net.bodies() == [{"jsonrpc": "2.0", "id": 1, "method": "getBalance",
                               "params": [ADDRESS, {"commitment": "confirmed"}]}]


def test_only_the_canonical_call_reaches_the_upstream(w):
    body = {"jsonrpc": "2.0", "id": 3, "method": "getLatestBlockhash", "params": [], "smuggled": "x"}
    assert w.client.post("/api/solana/rpc/devnet", json=body).status_code == 200
    assert "smuggled" not in w.net.bodies()[0]


def test_devnet_send_and_airdrop_are_proxied(w):
    assert w.call("sendTransaction", ["AQID", {"encoding": "base64"}]).status_code == 200
    assert w.call("requestAirdrop", [ADDRESS, 1_000_000_000]).status_code == 200
    assert [b["method"] for b in w.net.bodies()] == ["sendTransaction", "requestAirdrop"]


@pytest.mark.parametrize("method", ["getProgramAccounts", "getLargestAccounts", "setLogFilter", "minimumLedgerSlot"])
def test_a_method_the_app_never_calls_is_refused_before_the_upstream(w, method):
    res = w.call(method)
    assert res.status_code == 403 and reason(res) == "method_not_allowed"
    assert w.net.seen == []


def test_mainnet_send_is_refused_until_the_owner_says_yes(w):
    assert w.rpc.MAINNET_SEND_APPROVED is False
    res = w.call("sendTransaction", ["AQID", {"encoding": "base64"}], cluster="mainnet")
    assert res.status_code == 403 and reason(res) == "mainnet_send_not_approved"
    assert w.net.seen == []


def test_mainnet_airdrop_is_refused(w):
    res = w.call("requestAirdrop", [ADDRESS, 1], cluster="mainnet")
    assert res.status_code == 403 and reason(res) == "airdrop_is_devnet_only"
    assert w.net.seen == []


def test_mainnet_reads_go_to_the_mainnet_upstream(w):
    assert w.call("getAccountInfo", [ADDRESS, {"encoding": "jsonParsed"}], cluster="mainnet").status_code == 200
    assert str(w.net.seen[0].url) == MAINNET


def test_a_sendtransaction_hidden_in_a_mainnet_batch_is_refused(w):
    batch = [{"jsonrpc": "2.0", "id": 1, "method": "getBalance", "params": [ADDRESS]},
             {"jsonrpc": "2.0", "id": 2, "method": "sendTransaction", "params": ["AQID"]}]
    res = w.client.post("/api/solana/rpc/mainnet", json=batch)
    assert res.status_code == 403 and reason(res) == "mainnet_send_not_approved"
    assert w.net.seen == []


def test_a_small_batch_is_proxied_and_a_large_one_is_refused(w):
    item = {"jsonrpc": "2.0", "id": 1, "method": "getBalance", "params": [ADDRESS]}
    w.net.reply = lambda req: httpx.Response(200, json=[{"jsonrpc": "2.0", "id": 1, "result": 1}] * 2)
    assert w.client.post("/api/solana/rpc/devnet", json=[item, item]).status_code == 200
    res = w.client.post("/api/solana/rpc/devnet", json=[item] * (w.rpc.MAX_BATCH + 1))
    assert res.status_code == 400 and reason(res) == "batch_size"
    assert w.client.post("/api/solana/rpc/devnet", json=[]).status_code == 400
    assert len(w.net.seen) == 1


@pytest.mark.parametrize("body", [
    b"not json",
    b'{"jsonrpc":"1.0","id":1,"method":"getBalance","params":[]}',
    b'{"jsonrpc":"2.0","id":1,"method":"getBalance","params":{"a":1}}',
    b'{"jsonrpc":"2.0","id":{"x":1},"method":"getBalance","params":[]}',
    b'"getBalance"',
])
def test_malformed_requests_are_400(w, body):
    res = w.client.post("/api/solana/rpc/devnet", content=body, headers={"content-type": "application/json"})
    assert res.status_code == 400
    assert w.net.seen == []


def test_an_oversized_body_is_refused(w):
    res = w.call("sendTransaction", ["A" * (w.rpc.MAX_BODY + 1)])
    assert res.status_code == 413 and reason(res) == "body_too_large"
    assert w.net.seen == []


def test_unknown_cluster_is_404(w):
    assert w.call("getBalance", cluster="testnet").status_code == 404


def test_per_ip_limit_is_429_with_retry_after_and_other_phones_still_pass(w):
    w.rpc.limiter = w.rpc.Limiter(per_ip_minute=3)
    for _ in range(3):
        assert w.call("getBalance", [ADDRESS], ip="198.51.100.1").status_code == 200
    res = w.call("getBalance", [ADDRESS], ip="198.51.100.1")
    assert res.status_code == 429 and reason(res) == "rate_limited"
    assert 1 <= int(res.headers["retry-after"]) <= 61
    assert w.call("getBalance", [ADDRESS], ip="198.51.100.2").status_code == 200
    assert len(w.net.seen) == 4


def test_global_ceilings_bound_the_key_whatever_the_ip(w):
    w.rpc.limiter = w.rpc.Limiter(global_minute=2)
    assert w.call("getBalance", ip="198.51.100.1").status_code == 200
    assert w.call("getBalance", ip="198.51.100.2").status_code == 200
    res = w.call("getBalance", ip="198.51.100.3")
    assert res.status_code == 429 and reason(res) == "busy"

    w.rpc.limiter = w.rpc.Limiter(global_day=1)
    assert w.call("getBalance", ip="198.51.100.4").status_code == 200
    res = w.call("getBalance", ip="198.51.100.5")
    assert res.status_code == 429 and reason(res) == "daily_budget"


def test_a_batch_counts_every_call_against_the_limit(w):
    w.rpc.limiter = w.rpc.Limiter(per_ip_minute=3)
    item = {"jsonrpc": "2.0", "id": 1, "method": "getBalance", "params": [ADDRESS]}
    res = w.client.post("/api/solana/rpc/devnet", json=[item] * 4)
    assert res.status_code == 429
    assert w.net.seen == []


def test_airdrop_has_its_own_tight_daily_quota(w):
    w.rpc.limiter = w.rpc.Limiter(airdrop_ip_day=1)
    assert w.call("requestAirdrop", [ADDRESS, 1]).status_code == 200
    res = w.call("requestAirdrop", [ADDRESS, 1])
    assert res.status_code == 429 and reason(res) == "airdrop_quota"
    assert w.call("getBalance", [ADDRESS]).status_code == 200


def test_without_an_upstream_the_door_is_503_and_the_phone_goes_public(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, devnet="", mainnet="")
    for cluster in ("devnet", "mainnet"):
        res = w.call("getBalance", [ADDRESS], cluster=cluster)
        assert res.status_code == 503 and reason(res) == "rpc proxy is not configured"
    assert w.net.seen == []


def test_mainnet_stays_shut_when_only_devnet_is_configured(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, mainnet="")
    assert w.call("getBalance", [ADDRESS], cluster="mainnet").status_code == 503
    assert w.call("getBalance", [ADDRESS], cluster="devnet").status_code == 200


def test_a_plain_http_upstream_counts_as_not_configured(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, devnet=f"http://devnet.upstream.test/?api-key={SECRET}")
    assert w.call("getBalance", [ADDRESS]).status_code == 503
    assert w.net.seen == []


def _leaks(res, caplog) -> list[str]:
    out = []
    if SECRET in res.text or "api-key" in res.text:
        out.append(f"response: {res.text!r}")
    if any(SECRET in v for v in res.headers.values()):
        out.append("response headers")
    if SECRET in caplog.text:
        out.append("log")
    return out


@pytest.mark.parametrize("cluster", ["devnet", "mainnet"])
@pytest.mark.parametrize("failure", ["connect", "timeout", "status_500_echo", "status_401_echo", "reply_echo", "status_429"])
def test_an_upstream_failure_never_carries_the_key_to_the_phone_or_the_log(w, caplog, cluster, failure):
    def reply(request: httpx.Request) -> httpx.Response:
        if failure == "connect":
            raise httpx.ConnectError(f"[Errno -2] Name or service not known: {request.url}", request=request)
        if failure == "timeout":
            raise httpx.ReadTimeout(f"timed out reading {request.url}", request=request)
        if failure == "status_429":
            return httpx.Response(429, headers={"retry-after": "5"}, text=f"slow down {request.url}")
        if failure == "reply_echo":
            return httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "error": {
                "code": -32000, "message": f"key {SECRET} over quota at {request.url}"}})
        code = 500 if failure == "status_500_echo" else 401
        return httpx.Response(code, json={"error": f"bad request to {request.url}", "url": str(request.url)})

    w.net.reply = reply
    caplog.set_level(logging.DEBUG)
    res = w.call("getBalance", [ADDRESS], cluster=cluster)
    assert res.status_code in (429, 502, 504)
    assert len(w.net.seen) == 1, "апстрім мав отримати запит, інакше тест не міряє витік"
    assert _leaks(res, caplog) == []


def test_a_successful_call_does_not_write_the_key_or_the_address_to_the_log(w, caplog):
    caplog.set_level(logging.DEBUG)
    assert w.call("getBalance", [ADDRESS]).status_code == 200
    assert w.call("sendTransaction", ["AQIDBAUG", {"encoding": "base64"}]).status_code == 200
    assert SECRET not in caplog.text
    assert ADDRESS not in caplog.text
    assert "AQIDBAUG" not in caplog.text


def test_upstream_429_tells_the_phone_to_move_on(w):
    w.net.reply = lambda req: httpx.Response(429, headers={"retry-after": "7"})
    res = w.call("getBalance", [ADDRESS])
    assert res.status_code == 429 and reason(res) == "upstream_busy"
    assert res.headers["retry-after"] == "7"


@pytest.mark.skipif(not SOLANA_RPC_KT.is_dir(), reason="дерева телефона поряд немає")
def test_the_allowlist_is_exactly_what_the_phone_calls():
    """Метод, доданий у SolanaRpc.kt без рядка тут, мовчки пішов би повз наш ключ на
    публічний вузол; метод тут, якого телефон не кличе, — зайві двері до ключа."""
    called = set()
    for kt in SOLANA_RPC_KT.glob("*.kt"):
        called |= set(re.findall(r'\bcall\(\s*"([A-Za-z]+)"', kt.read_text(encoding="utf-8")))
    import solana_rpc

    assert len(called) >= 10, f"знайшли лише {sorted(called)} — розбирач зламався, а не телефон замовк"
    assert called == set(solana_rpc.ALLOWED), (
        f"лише телефон: {sorted(called - solana_rpc.ALLOWED)}, лише проксі: {sorted(solana_rpc.ALLOWED - called)}"
    )


@pytest.mark.skipif(not SOLANA_RPC_KT.is_dir(), reason="дерева телефона поряд немає")
def test_the_phone_knocks_on_this_route_with_these_cluster_names():
    import solana_rpc

    assert "/api/solana/rpc/${cluster.wire}" in (SOLANA_RPC_KT / "SolanaRpc.kt").read_text(encoding="utf-8")
    money = (SOLANA_RPC_KT / "Money.kt").read_text(encoding="utf-8")
    wires = set(re.findall(r'^\s*[A-Z]+\("([a-z]+)", "https://', money, re.M))
    assert wires == set(solana_rpc.UPSTREAM_ENV), f"телефон: {sorted(wires)}, сервер: {sorted(solana_rpc.UPSTREAM_ENV)}"
