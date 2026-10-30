"""Розум для телефона без ключа: Gemini через наш ключ, рівно в тому вигляді, що й у Google.

Мережа підмінена httpx.MockTransport: апстрім бачить рівно те, що побачив би Google.
Усе ходить через main.app — тобто через змонтований маршрутизатор, а не через модуль.
"""
import asyncio
import hashlib
import json
import logging
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

MODULES = ("main", "db", "license_crypto", "providers", "mailer", "accounts", "storage",
           "solana_pay", "sponsor", "solana_rpc", "brain")
KEY = "fake-gemini-key-7f3a9c1e5b2d"
DEVICE = "dEv1ce_Id-0123456789abcd"
MODEL = "gemini-3.8-flash"
PATH = f"/api/brain/v1beta/models/{MODEL}:streamGenerateContent?alt=sse"
GOOGLE = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:streamGenerateContent?alt=sse"
PROMPT = "секретне питання про зустріч у кавʼярні"
REPLY = "відповідь-яку-ніхто-не-логує"
SSE = (
    b'data: {"candidates":[{"content":{"parts":[{"text":"' + REPLY.encode() + b'"}],"role":"model"}}]}\r\n\r\n'
    b'data: {"candidates":[{"content":{"parts":[{"text":"!"}],"role":"model"},"finishReason":"STOP"}],'
    b'"usageMetadata":{"totalTokenCount":9}}\r\n\r\n'
)
BRAIN_PROFILE_KT = (SERVER_DIR.parents[1] / "phantom-companion/core-ai/src/main/java/local/phantom/companion"
                    "/core/ai/BrainProfile.kt")


def body(max_tokens=4096, **extra):
    doc = {
        "contents": [{"role": "user", "parts": [{"text": PROMPT}]}],
        "generationConfig": {"maxOutputTokens": max_tokens, "temperature": 0.7},
        "systemInstruction": {"parts": [{"text": "Answer in English."}]},
        "tools": [{"functionDeclarations": [{"name": "draft_meetup", "description": "d",
                                             "parameters": {"type": "OBJECT", "properties": {}}}]},
                  {"google_search": {}}],
    }
    doc.update(extra)
    return doc


class Upstream:
    def __init__(self):
        self.seen: list[httpx.Request] = []
        self.reply = lambda req: httpx.Response(200, content=SSE, headers={"content-type": "text/event-stream"})

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.seen.append(request)
        return self.reply(request)

    def sent(self) -> dict:
        return json.loads(self.seen[-1].content)


class World:
    def __init__(self, client, brain, net):
        self.client, self.brain, self.net = client, brain, net

    def ask(self, doc=None, device=DEVICE, ip="203.0.113.7", path=PATH, raw=None, header="x-nightpost-device"):
        headers = {"fly-client-ip": ip, "content-type": "application/json"}
        if device is not None:
            headers[header] = device
        content = raw if raw is not None else json.dumps(doc if doc is not None else body()).encode()
        return self.client.post(path, content=content, headers=headers)


def _behind_fly_proxy(app):
    async def wrapped(scope, receive, send):
        if scope["type"] == "http":
            scope = dict(scope, client=("127.0.0.1", 50000))
        await app(scope, receive, send)
    return wrapped


VERTEX_ENV = ("VERTEX_PROJECT", "VERTEX_LOCATION", "GOOGLE_SA_JSON", "GOOGLE_APPLICATION_CREDENTIALS")


def _boot(tmp_path, monkeypatch, key=KEY, daily=None, vertex=None, **limits):
    monkeypatch.setenv("LICENSE_DB", str(tmp_path / "brain.db"))
    monkeypatch.setenv("LICENSE_SIGNING_KEY", str(tmp_path / "signing.pem"))
    monkeypatch.setenv("LICENSE_POLICY", str(tmp_path / "policy.json"))
    monkeypatch.setenv("ADMIN_TOKEN", "test-admin-token")
    named = {"BRAIN_PER_IP_MINUTE": None, "BRAIN_PER_IP_DAY": None, "BRAIN_DAILY_GLOBAL": None}
    named.update({"BRAIN_" + k.upper(): v for k, v in limits.items()})
    named.update({name: (vertex or {}).get(name) for name in VERTEX_ENV})
    for name, value in (("GEMINI_API_KEY", key), ("BRAIN_DAILY_PER_DEVICE", daily), *named.items()):
        if value:
            monkeypatch.setenv(name, value)
        else:
            monkeypatch.delenv(name, raising=False)
    for mod in MODULES:
        sys.modules.pop(mod, None)
    import main as server
    import brain
    from fastapi.testclient import TestClient

    net = Upstream()
    brain.transport = httpx.MockTransport(net)
    return World(TestClient(_behind_fly_proxy(server.app)), brain, net)


@pytest.fixture()
def w(tmp_path, monkeypatch):
    return _boot(tmp_path, monkeypatch)


def error(res):
    return res.json().get("error")


def test_status_says_yes_with_the_models_and_the_daily_allowance(w):
    res = w.client.get("/api/brain/status")
    assert res.status_code == 200
    assert res.json() == {"brain": True, "live": True, "models": list(w.brain.MODELS), "profiles": w.brain.PROFILE_DEFAULTS,
                          "daily": 10}
    assert KEY not in res.text


def test_status_says_no_without_a_key(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, key="")
    said = w.client.get("/api/brain/status").json()
    assert said["brain"] is False
    assert "reason" not in said  # «ключа немає» і «ліміт вичерпано» телефон не плутає


def test_status_turns_false_once_the_global_daily_budget_is_spent(w):
    w.brain.limiter = w.brain.Limiter(global_day=1)
    assert w.ask().status_code == 200
    said = w.client.get("/api/brain/status").json()
    assert said["brain"] is False
    assert said["reason"] == "daily_budget"


def test_status_turns_false_for_a_device_that_spent_its_own_allowance(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, daily="1")
    assert w.ask(device=DEVICE).status_code == 200
    spent = w.client.get("/api/brain/status", headers={"x-phantom-device": DEVICE})
    assert spent.json()["brain"] is False
    assert spent.json()["reason"] == "daily_limit"
    # інший апарат того ж сервера ще має свою добу — його статус «є»
    other = w.client.get("/api/brain/status", headers={"x-phantom-device": "another-install-id-0001"})
    assert other.json()["brain"] is True and "reason" not in other.json()
    fresh = w.client.get("/api/brain/status", headers={"x-phantom-device": "another-install-id-0001"})
    assert fresh.json()["brain"] is True


def test_the_nightpost_header_names_the_device_and_the_old_name_still_counts(tmp_path, monkeypatch):
    # Збірка 18 шле X-Nightpost-Device; телефони збірок до неї — X-Phantom-Device.
    # Обидва називають ту саму установку й ту саму добу.
    w = _boot(tmp_path, monkeypatch, daily="1")
    assert w.ask(device=DEVICE, header="x-nightpost-device").status_code == 200
    assert w.ask(device=DEVICE, header="x-nightpost-device", ip="198.51.100.1").status_code == 429
    assert w.ask(device=DEVICE, header="x-phantom-device", ip="198.51.100.2").status_code == 429
    spent = w.client.get("/api/brain/status", headers={"x-nightpost-device": DEVICE})
    assert spent.json()["reason"] == "daily_limit"
    assert w.ask(device="another-install-id-0001", header="x-nightpost-device", ip="198.51.100.3").status_code == 200


def test_without_a_key_the_stream_is_503_and_google_is_never_called(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, key="")
    res = w.ask()
    assert res.status_code == 503 and res.json() == {"brain": False}
    assert w.net.seen == []


def test_the_reply_is_googles_sse_byte_for_byte(w):
    res = w.ask()
    assert res.status_code == 200
    assert res.headers["content-type"].startswith("text/event-stream")
    assert res.content == SSE


def test_google_gets_its_own_url_with_the_key_in_a_header_only(w):
    w.ask(path=PATH + "&key=from-the-phone")
    [sent] = w.net.seen
    assert str(sent.url) == GOOGLE
    assert sent.headers["x-goog-api-key"] == KEY
    assert KEY not in str(sent.url)
    assert "x-phantom-device" not in sent.headers
    assert "x-nightpost-device" not in sent.headers
    assert "fly-client-ip" not in sent.headers


def test_the_body_passes_through_function_declarations_but_not_billed_google_tools(w):
    doc = body(max_tokens=1024)
    w.ask(doc)
    expected = dict(doc, tools=[doc["tools"][0]])  # functionDeclarations stays, google_search is cut
    expected["generationConfig"] = dict(doc["generationConfig"], thinkingConfig={"thinkingLevel": "LOW"})
    assert w.net.sent() == expected


@pytest.mark.parametrize("key", [
    "google_search", "googleSearch", "google_search_retrieval", "googleSearchRetrieval",
    "url_context", "urlContext", "code_execution", "codeExecution",
])
def test_billed_google_tools_never_reach_google_under_any_spelling(w, key):
    doc = body()
    doc["tools"] = [{key: {}}]
    w.ask(doc)
    assert "tools" not in w.net.sent()


def test_a_devices_own_function_declarations_still_reach_google_with_no_billed_tool(w):
    doc = body()
    doc["tools"] = [doc["tools"][0]]  # functionDeclarations only, no google_search
    w.ask(doc)
    assert w.net.sent()["tools"] == doc["tools"]


@pytest.mark.parametrize("key", ["googleMaps", "fileSearch", "computerUse", "enterpriseWebSearch",
                                 "retrieval", "someToolGoogleShipsNextYear"])
def test_only_the_devices_own_function_declarations_pass_not_a_list_of_known_bad_tools(w, key):
    doc = body()
    doc["tools"] = [{key: {}}]
    w.ask(doc)
    assert "tools" not in w.net.sent()


def test_a_tool_that_mixes_function_declarations_with_a_billed_one_loses_only_the_billed_one(w):
    declarations = body()["tools"][0]["functionDeclarations"]
    doc = body()
    doc["tools"] = [{"functionDeclarations": declarations, "googleSearch": {}, "google_maps": {}}]
    w.ask(doc)
    assert w.net.sent()["tools"] == [{"functionDeclarations": declarations}]


@pytest.mark.parametrize("part", [
    {"fileData": {"mimeType": "video/mp4", "fileUri": "https://www.youtube.com/watch?v=aaaaaaaaaaa"}},
    {"file_data": {"mime_type": "video/mp4", "file_uri": "https://www.youtube.com/watch?v=aaaaaaaaaaa"}},
])
def test_a_file_reference_is_refused_before_google_because_google_would_fetch_and_bill_it(w, part):
    doc = body()
    doc["contents"][0]["parts"].append(part)
    res = w.ask(doc)
    assert res.status_code == 400 and error(res) == "file_reference_not_allowed"
    assert w.net.seen == []


def test_inline_data_from_the_phone_still_passes(w):
    doc = body()
    doc["contents"][0]["parts"].append({"inline_data": {"mime_type": "image/jpeg", "data": "QUJD"}})
    assert w.ask(doc).status_code == 200
    assert w.net.sent()["contents"] == doc["contents"]


@pytest.mark.parametrize("asked, sent", [(8192, 2048), (2048, 2048), (512, 512), (None, 2048), ("lots", 2048)])
def test_max_output_tokens_is_capped_at_2048(w, asked, sent):
    doc = body(max_tokens=asked)
    if asked is None:
        del doc["generationConfig"]["maxOutputTokens"]
    w.ask(doc)
    config = w.net.sent()["generationConfig"]
    assert config["maxOutputTokens"] == sent
    assert config["temperature"] == 0.7


def test_a_body_without_generation_config_still_gets_the_cap(w):
    doc = body()
    del doc["generationConfig"]
    w.ask(doc)
    assert w.net.sent()["generationConfig"] == {"maxOutputTokens": 2048, "thinkingConfig": {"thinkingLevel": "LOW"}}


def test_a_snake_case_sibling_cannot_smuggle_a_bigger_cap_past_the_real_one(w):
    doc = body()
    doc["generation_config"] = {"max_output_tokens": 65536}
    w.ask(doc)
    sent = w.net.sent()
    assert "generation_config" not in sent
    assert sent["generationConfig"]["maxOutputTokens"] == 2048


def test_a_snake_case_field_inside_generation_config_is_dropped_not_forwarded(w):
    doc = body()
    doc["generationConfig"]["max_output_tokens"] = 65536
    w.ask(doc)
    sent = w.net.sent()["generationConfig"]
    assert "max_output_tokens" not in sent
    assert sent["maxOutputTokens"] == 2048


def test_a_body_over_256_kib_is_refused_before_google(w):
    doc = body(padding="x" * (256 * 1024))
    res = w.ask(doc)
    assert res.status_code == 413 and error(res)
    assert w.net.seen == []


def test_a_body_exactly_at_the_limit_passes(w):
    doc = body()
    doc["padding"] = ""
    doc["padding"] = "x" * (256 * 1024 - len(json.dumps(doc).encode()))
    assert len(json.dumps(doc).encode()) == 256 * 1024
    assert w.ask(doc).status_code == 200


@pytest.mark.parametrize("raw", [b"not json", b"[1,2]", b'"text"', b'{"generationConfig": 5}'])
def test_a_body_that_is_not_a_gemini_request_is_400(w, raw):
    res = w.ask(raw=raw)
    assert res.status_code == 400 and error(res)
    assert w.net.seen == []


@pytest.mark.parametrize("device", [None, "", "a" * 21, "x" * 65, "dEv1ce Id 0123456789abcd", "dEv1ce.Id+0123456789abcd"])
def test_a_missing_or_malformed_device_header_is_400(w, device):
    res = w.ask(device=device)
    assert res.status_code == 400 and error(res)
    assert w.net.seen == []


@pytest.mark.parametrize("device", ["a" * 22, "Z_-9" * 16])
def test_device_ids_from_22_to_64_chars_pass(w, device):
    assert w.ask(device=device).status_code == 200


@pytest.mark.parametrize("model", ["gemini-3.8-pro", "gemini-2.5-pro", "imagen-4", "gemini-3.8-flash-x"])
def test_a_model_outside_the_allowlist_is_404_and_never_reaches_google(w, model):
    res = w.ask(path=f"/api/brain/v1beta/models/{model}:streamGenerateContent?alt=sse")
    assert res.status_code == 404
    assert w.net.seen == []


def test_every_allowed_model_is_flash_class(w):
    assert w.brain.MODELS and all("flash" in m for m in w.brain.MODELS)


def test_a_device_gets_its_daily_allowance_then_429_until_midnight(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, daily="3")
    assert w.client.get("/api/brain/status").json()["daily"] == 3
    for i in range(3):
        assert w.ask(ip=f"198.51.100.{i}").status_code == 200
    res = w.ask(ip="198.51.100.9")
    assert res.status_code == 429 and error(res)
    assert 1 <= int(res.headers["retry-after"]) <= 86400
    assert w.ask(device="another-install-id-0001").status_code == 200
    assert len(w.net.seen) == 4


def test_an_ip_cannot_dodge_its_device_cap_by_minting_a_fresh_install_id_each_time(w):
    w.brain.limiter = w.brain.Limiter(per_ip_day=3)
    for i in range(2):
        assert w.ask(device=f"install-id-number-{i:05d}").status_code == 200
    res = w.ask(device="install-id-number-99999")
    assert res.status_code == 429 and error(res) == "daily_limit"
    assert 1 <= int(res.headers["retry-after"]) <= 86400
    assert w.net.seen and len(w.net.seen) == 2
    # a different IP is unaffected
    assert w.ask(device="install-id-number-99999", ip="198.51.100.200").status_code == 200


def test_an_ip_gets_its_minute_allowance_then_a_retry_after(w):
    w.brain.limiter = w.brain.Limiter(per_device_day=60)
    for _ in range(w.brain.limiter.per_ip_minute):
        assert w.ask(device=DEVICE).status_code == 200
    res = w.ask(device=DEVICE)
    assert res.status_code == 429 and error(res)
    assert 1 <= int(res.headers["retry-after"]) <= 61
    assert w.ask(device=DEVICE, ip="198.51.100.77").status_code == 200


def test_the_whole_server_stops_at_its_daily_ceiling(w):
    w.brain.limiter = w.brain.Limiter(global_day=2)
    assert w.ask(device="install-id-number-00001", ip="198.51.100.1").status_code == 200
    assert w.ask(device="install-id-number-00002", ip="198.51.100.2").status_code == 200
    res = w.ask(device="install-id-number-00003", ip="198.51.100.3")
    assert res.status_code == 429 and "retry-after" in res.headers


def test_default_limits_leave_room_for_a_venue_behind_one_nat(w):
    # Спадковий ліміт на пристрій зрівняно з BRAIN_DAY_KEY_ONLY (10), загальний — 1500.
    # Ліміт на IP — одна Wi-Fi зали чи один NAT оператора.
    limiter = w.brain.Limiter.from_env()
    assert (limiter.per_device_day, limiter.global_day) == (10, 1500)
    assert limiter.per_ip_minute >= 30 and limiter.per_ip_day >= 300
    assert limiter.per_ip_day < limiter.global_day  # один IP усе ще не з'їдає глобальну стелю


def test_the_ip_ceilings_are_settings_not_constants(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch, per_ip_minute="2", per_ip_day="5", daily_global="7")
    assert (w.brain.limiter.per_ip_minute, w.brain.limiter.per_ip_day, w.brain.limiter.global_day) == (2, 5, 7)
    assert w.ask(device="install-id-number-00001").status_code == 200
    assert w.ask(device="install-id-number-00002").status_code == 200
    assert w.ask(device="install-id-number-00003").status_code == 429


def test_a_host_cannot_rotate_ipv6_addresses_inside_its_own_slash_64(w):
    w.brain.limiter = w.brain.Limiter(per_ip_day=3)
    for i, host in enumerate(("2001:db8:1:2::1", "2001:db8:1:2:aaaa::2", "2001:db8:1:2:ffff:ffff:ffff:ffff")):
        assert w.ask(device=f"install-id-number-{i % 2:05d}", ip=host).status_code == 200
    spill = w.ask(device="install-id-number-00000", ip="2001:db8:1:2:1234:5678:9abc:def0")
    assert spill.status_code == 429 and error(spill) == "daily_budget"
    # сусідня /64 — інша людина
    assert w.ask(device="install-id-number-99999", ip="2001:db8:1:3::1").status_code == 200


def test_an_ipv4_mapped_ipv6_address_is_the_same_ip_as_its_ipv4(w):
    w.brain.limiter = w.brain.Limiter(per_ip_day=1)
    assert w.ask(device="install-id-number-00001", ip="203.0.113.7").status_code == 200
    assert w.ask(device="install-id-number-00002", ip="::ffff:203.0.113.7").status_code == 429


def _leaks(res, caplog) -> list[str]:
    out = []
    if KEY in res.text:
        out.append(f"response: {res.text!r}")
    if any(KEY in v for v in res.headers.values()):
        out.append("response headers")
    if KEY in caplog.text:
        out.append("log")
    return out


@pytest.mark.parametrize("status", [400, 404, 429, 500])
def test_an_upstream_error_keeps_its_status_but_never_its_body(w, caplog, status):
    w.net.reply = lambda req: httpx.Response(
        status, json={"error": {"message": f"API key {req.headers['x-goog-api-key']} invalid", "url": str(req.url)}})
    caplog.set_level(logging.DEBUG)
    res = w.ask()
    assert res.status_code == status
    assert set(res.json()) == {"error"} and len(res.text) < 80
    assert "API key" not in res.text and "generativelanguage" not in res.text
    assert _leaks(res, caplog) == []


@pytest.mark.parametrize("status", [401, 403, 503])
def test_google_refusing_our_key_or_overloaded_never_wears_the_no_brain_signal(w, caplog, status):
    # 401/403/503 від сервера Nightpost телефон читає як «розуму тут немає» і гасить
    # маршрут на десять хвилин; одне перевантаження Google не має вимикати помічника.
    w.net.reply = lambda req: httpx.Response(
        status, json={"error": {"message": f"API key {req.headers['x-goog-api-key']} invalid"}})
    caplog.set_level(logging.DEBUG)
    res = w.ask()
    assert res.status_code == 502
    assert res.json() == {"error": "upstream"}
    assert "brain" not in res.json()
    assert _leaks(res, caplog) == []


@pytest.mark.parametrize("status", [401, 403, 503])
def test_those_refusals_do_not_spend_the_devices_allowance(tmp_path, monkeypatch, status):
    w = _boot(tmp_path, monkeypatch, daily="1")
    w.net.reply = lambda req: httpx.Response(status, json={"error": "nope"})
    assert w.ask().status_code == 502
    assert w.ask().status_code == 502  # не локальний 429 daily_limit
    assert len(w.net.seen) == 2


def test_a_refused_key_is_visible_to_the_operator_without_the_body(w, caplog):
    w.net.reply = lambda req: httpx.Response(403, json={"error": {"message": req.headers["x-goog-api-key"]}})
    caplog.set_level(logging.DEBUG)
    w.ask()
    assert "403" in caplog.text and KEY not in caplog.text


@pytest.mark.parametrize("status", [404, 429, 500, 503])
def test_any_upstream_refusal_is_visible_to_the_operator_with_status_and_model(w, caplog, status):
    # Доступ до /api/brain у журналі приглушено, тож без цього рядка 502 «upstream»
    # не відрізнити: перевантаження Google, чужа модель чи ключ.
    w.net.reply = lambda req: httpx.Response(status, json={"error": {"message": req.headers["x-goog-api-key"]}})
    caplog.set_level(logging.WARNING)
    w.ask()
    line = [r.getMessage() for r in caplog.records if r.levelno >= logging.WARNING and "upstream" in r.getMessage()]
    assert line and str(status) in line[0] and "gemini" in line[0]
    assert KEY not in caplog.text


@pytest.mark.parametrize("status", [403, 503])
def test_the_operator_sees_googles_reason_but_never_a_key(w, caplog, status):
    w.net.reply = lambda req: httpx.Response(status, json={"error": {
        "status": "UNAVAILABLE", "message": f"The model is overloaded. {req.headers['x-goog-api-key']} "
                                            "AIzaFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAK"}})
    caplog.set_level(logging.WARNING)
    w.ask()
    assert "UNAVAILABLE" in caplog.text and "overloaded" in caplog.text
    assert KEY not in caplog.text and "AIzaSy" not in caplog.text


def test_an_upstream_429_without_a_retry_after_header_still_gets_one(w):
    w.net.reply = lambda req: httpx.Response(429, json={"error": "rate"})
    res = w.ask()
    assert res.status_code == 429
    assert res.headers["retry-after"] == "30"


@pytest.mark.parametrize("upstream_status, seen", [(500, 500), (503, 502), (429, 429)])
def test_a_failed_upstream_call_refunds_the_devices_daily_allowance(tmp_path, monkeypatch, upstream_status, seen):
    w = _boot(tmp_path, monkeypatch, daily="1")
    w.net.reply = lambda req: httpx.Response(upstream_status, json={"error": "nope"})
    first = w.ask()
    assert first.status_code == seen and error(first) == "upstream"
    # without a refund the device's one slot is already spent and this would be
    # a local 429 "daily_limit" instead of reaching (and failing at) google again
    second = w.ask()
    assert second.status_code == seen and error(second) == "upstream"
    assert len(w.net.seen) == 2


@pytest.mark.parametrize("failure, status", [("connect", 502), ("timeout", 504)])
def test_a_dead_upstream_is_a_short_error_without_the_key(w, caplog, failure, status):
    def reply(request):
        if failure == "connect":
            raise httpx.ConnectError(f"cannot reach {request.url} with {request.headers}", request=request)
        raise httpx.ReadTimeout(f"timed out {request.headers}", request=request)

    w.net.reply = reply
    caplog.set_level(logging.DEBUG)
    res = w.ask()
    assert res.status_code == status and set(res.json()) == {"error"}
    assert _leaks(res, caplog) == []


@pytest.mark.parametrize("failure, status", [("connect", 502), ("timeout", 504)])
def test_a_dead_upstream_also_refunds_the_devices_daily_allowance(tmp_path, monkeypatch, failure, status):
    w = _boot(tmp_path, monkeypatch, daily="1")

    def reply(request):
        if failure == "connect":
            raise httpx.ConnectError("unreachable", request=request)
        raise httpx.ReadTimeout("timed out", request=request)

    w.net.reply = reply
    first = w.ask()
    assert first.status_code == status
    second = w.ask()
    assert second.status_code == status  # still reaches google, not a local daily_limit 429
    assert len(w.net.seen) == 2


ASK_LINE = re.compile(r"^brain ask device=(?P<device>[0-9a-f]{8}|-) profile=(?P<profile>\S+) "
                      r"model=(?P<model>\S+) asked=(?P<asked>\S+) "
                      r"status=(?P<status>\d+) first_ms=(?P<first>\d+|-) total_ms=(?P<total>\d+) upstream=(?P<upstream>vertex|gemini|none)$")


def asks(caplog):
    return [r for r in caplog.records if r.name == "phantom.brain" and r.getMessage().startswith("brain ask ")]


def test_every_ask_is_one_warning_line_with_the_first_chunk_and_the_total(w, caplog):
    caplog.set_level(logging.WARNING)
    assert w.ask().content == SSE
    [record] = asks(caplog)
    assert record.levelno == logging.WARNING
    line = ASK_LINE.fullmatch(record.getMessage())
    assert line, record.getMessage()
    assert line["device"] == hashlib.sha256(DEVICE.encode()).hexdigest()[:8]
    assert (line["profile"], line["model"], line["asked"], line["status"], line["upstream"]) == (
        "act", MODEL, MODEL, "200", "gemini")
    assert int(line["first"]) <= int(line["total"])
    for private in (KEY, DEVICE, PROMPT, REPLY, "draft_meetup", "203.0.113.7"):
        assert private not in caplog.text


def test_the_ask_line_names_vertex_when_the_call_went_there(v, caplog):
    caplog.set_level(logging.WARNING)
    assert v.ask().status_code == 200
    [record] = asks(caplog)
    assert ASK_LINE.fullmatch(record.getMessage())["upstream"] == "vertex"
    assert TOKEN not in caplog.text


class Slow(httpx.AsyncByteStream):
    def __init__(self, before_first, before_second):
        self.delays = (before_first, before_second)

    async def __aiter__(self):
        first, rest = SSE.split(b"\r\n\r\n", 1)
        await asyncio.sleep(self.delays[0])
        yield first + b"\r\n\r\n"
        await asyncio.sleep(self.delays[1])
        yield rest


def test_first_ms_stops_at_the_first_chunk_and_total_ms_at_the_last(w, caplog):
    caplog.set_level(logging.WARNING)
    w.net.reply = lambda req: httpx.Response(200, headers={"content-type": "text/event-stream"}, stream=Slow(0.15, 0.4))
    assert w.ask().content == SSE
    line = ASK_LINE.fullmatch(asks(caplog)[0].getMessage())
    first, total = int(line["first"]), int(line["total"])
    assert 150 <= first < 400, line.string
    assert total - first >= 400, line.string


def test_a_refusal_is_logged_the_same_narrow_way(w, caplog):
    caplog.set_level(logging.WARNING)
    w.ask(device="bad")
    w.ask(raw=b"x" * (300 * 1024))
    lines = [r.getMessage() for r in asks(caplog)]
    assert len(lines) == 2
    parsed = [ASK_LINE.fullmatch(line) for line in lines]
    assert all(parsed), lines
    assert [m["status"] for m in parsed] == ["400", "413"]
    assert all(m["first"] == "-" for m in parsed)
    assert all("bad" not in line and "xxxx" not in line for line in lines)


def test_a_server_without_a_brain_still_logs_the_ask(tmp_path, monkeypatch, caplog):
    w = _boot(tmp_path, monkeypatch, key="")
    caplog.set_level(logging.WARNING)
    assert w.ask().status_code == 503
    [record] = asks(caplog)
    line = ASK_LINE.fullmatch(record.getMessage())
    assert (line["status"], line["upstream"]) == ("503", "none")


def _upload_that_stops_halfway(w, sent: bytes):
    scope = {
        "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "POST",
        "path": f"/api/brain/v1beta/models/{MODEL}:streamGenerateContent", "raw_path": b"",
        "query_string": b"alt=sse", "scheme": "http", "server": ("test", 80), "client": ("127.0.0.1", 50000),
        "headers": [(b"x-nightpost-device", DEVICE.encode()), (b"fly-client-ip", b"203.0.113.7"),
                    (b"content-type", b"application/json"), (b"content-length", str(len(sent) * 4).encode())],
    }
    script = iter([{"type": "http.request", "body": sent, "more_body": True}, {"type": "http.disconnect"}])

    async def receive():
        return next(script)

    async def send(message):
        pass

    asyncio.run(w.client.app(scope, receive, send))


def test_a_phone_that_hangs_up_during_the_upload_is_one_line_and_no_traceback(w, caplog):
    caplog.set_level(logging.DEBUG)
    _upload_that_stops_halfway(w, b'{"contents":[{"role":"user"')
    [record] = [r for r in caplog.records if r.name == "phantom.brain"]
    device = hashlib.sha256(DEVICE.encode()).hexdigest()[:8]
    assert record.levelno == logging.WARNING
    assert record.getMessage() == f"brain ask device={device} status=499 client_closed_during_upload bytes=27"
    assert all(r.exc_info is None for r in caplog.records)
    assert w.net.seen == []
    for private in (DEVICE, "203.0.113.7", "contents"):
        assert private not in caplog.text


def test_a_hung_up_upload_costs_the_device_nothing(w):
    w.brain.limiter = w.brain.Limiter(per_device_day=1)
    _upload_that_stops_halfway(w, b'{"contents":')
    assert w.ask().status_code == 200


@pytest.mark.skipif(not BRAIN_PROFILE_KT.is_file(), reason="дерева телефона поряд немає")
def test_every_phone_profile_model_is_on_the_server_allowlist():
    import brain

    source = BRAIN_PROFILE_KT.read_text(encoding="utf-8")
    enum = re.search(r"enum class BrainProfile\(val wire: String\) \{(.*?)\}", source, re.S)
    block = re.search(r"val DEFAULT: Map<BrainProfile, String> = mapOf\((.*?)\)\n", source, re.S)
    assert enum and block, "BrainProfile.kt змінив форму — розбирач зламався"
    wires = set(re.findall(r'\("([a-z]+)"\)', enum.group(1)))
    assert wires == {"chat", "act", "voice", "summary"}
    pairs = dict(re.findall(r'BrainProfile\.([A-Z]+) to "([^"]+)"', block.group(1)))
    assert {k.lower() for k in pairs} == wires
    assert set(pairs.values()) <= set(brain.MODELS)


def test_the_env_example_names_every_setting_empty():
    # Окремий brain.env.example, а не .env.example з брифу: у server/ немає спільного
    # .env.example, а sponsor і solana-rpc лежать так само — по файлу на можливість.
    text = (SERVER_DIR / "brain.env.example").read_text(encoding="utf-8")
    settings = dict(line.split("=", 1) for line in text.splitlines() if "=" in line and not line.startswith("#"))
    assert settings == {"GEMINI_API_KEY": "", "BRAIN_DAILY_PER_DEVICE": "", "BRAIN_PER_IP_MINUTE": "",
                        "BRAIN_PER_IP_DAY": "", "BRAIN_DAILY_GLOBAL": "", "BRAIN_DAY_KEY_ONLY": "",
                        "BRAIN_DAY_FREE": "", "BRAIN_DAY_PERSONAL": "", "BRAIN_DAY_CREW": "",
                        "BRAIN_DAY_LEGACY_TOTAL": "", "BRAIN_ATTEST_PER_IP_DAY": "",
                        "BRAIN_KEY_ONLY_IP_FACTOR": "", "PLAY_INTEGRITY_DECRYPTION_KEY": "",
                        "PLAY_INTEGRITY_VERIFICATION_KEY": "", "VERTEX_PROJECT": "", "VERTEX_LOCATION": "",
                        "GOOGLE_SA_JSON": "", "GOOGLE_APPLICATION_CREDENTIALS": ""}


def _access_line(logger, path):
    # Рівно той рядок, який друкує uvicorn.access: адреса клієнта, метод, шлях із запитом.
    logger.info('%s - "%s %s HTTP/%s" %d', "203.0.113.7:51234", "POST", path, "1.1", 200)


def test_uvicorns_access_log_never_writes_a_brain_request(w, caplog):
    # Журнал доступу uvicorn — окремий від нашого: адреса, час і модель у ньому
    # зробили б із «лише хеш, модель, код і час» неправду.
    access = logging.getLogger("uvicorn.access")
    caplog.set_level(logging.DEBUG)
    _access_line(access, PATH)
    _access_line(access, "/api/brain/status")
    _access_line(access, "/healthz")
    lines = [r.getMessage() for r in caplog.records if r.name == "uvicorn.access"]
    assert len(lines) == 1 and "/healthz" in lines[0], lines


@pytest.mark.parametrize("raw", [
    b'{"a":' * 40000 + b"1" + b"}" * 40000,
    b"[" * 100000 + b"]" * 100000,
], ids=["objects", "lists"])
def test_a_body_nested_deep_enough_to_exhaust_the_parser_is_a_400_not_a_crash(w, raw):
    assert len(raw) < 256 * 1024
    res = w.ask(raw=raw)
    assert res.status_code == 400 and error(res) == "bad_json"
    assert w.net.seen == []


def test_the_security_page_row_says_what_happens_when_the_status_cannot_be_read():
    # Рядок страж обіцяє «якщо сервер каже, що помічника немає, більше нічого не йде».
    # Телефон питає і тоді, коли статус прочитати не вдалося (повільна мережа), —
    # сторінка мусить це казати, інакше обіцянка читається ширше, ніж виконується.
    page = (SERVER_DIR.parent / "src/i18n/security.ts").read_text(encoding="utf-8")
    rows = [m.group(0) for m in re.finditer(
        r"when: '(?:When you have not entered a Gemini key|Коли ви не вписали ключ Gemini)[^\n]*',", page)]
    assert len(rows) == 2, "очікувалось два рядки «коли» (EN і UK) для помічника без ключа"
    assert "cannot be read" in rows[0] and "anyway" in rows[0]
    assert "прочитати не вдалося" in rows[1] and "усе одно" in rows[1]
    assert "never replaced by ours" in rows[0] and "наш не підміняє" in rows[1]


PROJECT = "nightpost-test-project"
# Склеєно під час прогону: сканер секретів (і фільтр argon.py) не бачить у тексті тесту ключа, якого немає.
TOKEN = "ya29." + "a0fake-vertex-token-5d1e9c"
OTHER_TOKEN = "ya29." + "c0another-token-Xy_z"
OTHER_KEY = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"
PEM = "-----BEGIN " + "PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END " + "PRIVATE KEY-----"
SA_JSON = '{"type": "service_account"}'


def vertex_url(model, location="global", host="aiplatform.googleapis.com"):
    return (f"https://{host}/v1/projects/{PROJECT}/locations/{location}"
            f"/publishers/google/models/{model}:streamGenerateContent?alt=sse")


class FakeCredentials:
    """Замість service_account.Credentials з google-auth: без ключа й без мережі."""

    def __init__(self, ttl=3600, fail=None):
        self.ttl, self.fail = ttl, fail
        self.token = self.expiry = None
        self.on_loop: list[bool] = []

    def refresh(self, request):
        try:
            asyncio.get_running_loop()
            self.on_loop.append(True)
        except RuntimeError:
            self.on_loop.append(False)
        if self.fail:
            raise self.fail
        self.token = f"{TOKEN}{len(self.on_loop)}"
        self.expiry = datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(seconds=self.ttl)


def _boot_vertex(tmp_path, monkeypatch, key=KEY, creds=None, daily=None, **env):
    w = _boot(tmp_path, monkeypatch, key=key, daily=daily,
              vertex={"VERTEX_PROJECT": PROJECT, "GOOGLE_SA_JSON": SA_JSON, **env})
    w.creds = creds or FakeCredentials()
    w.brain._vertex_credentials = lambda: w.creds
    return w


@pytest.fixture()
def v(tmp_path, monkeypatch):
    return _boot_vertex(tmp_path, monkeypatch)


@pytest.mark.parametrize("env", [{}, {"GOOGLE_SA_JSON": "", "GOOGLE_APPLICATION_CREDENTIALS": "/run/sa.json"}],
                         ids=["sa-json", "credentials-path"])
def test_with_vertex_set_the_call_goes_to_vertex_with_a_bearer_and_no_key(tmp_path, monkeypatch, env):
    v = _boot_vertex(tmp_path, monkeypatch, **env)
    res = v.ask(path=PATH + "&key=from-the-phone")
    assert res.status_code == 200 and res.content == SSE
    [sent] = v.net.seen
    assert str(sent.url) == vertex_url(MODEL)
    assert sent.headers["authorization"] == f"Bearer {TOKEN}1"
    assert "x-goog-api-key" not in sent.headers
    assert "key=" not in str(sent.url) and KEY not in str(sent.url)
    assert "x-nightpost-device" not in sent.headers and "fly-client-ip" not in sent.headers


def test_vertex_gets_the_same_capped_body_without_billed_tools(v):
    v.ask(body(max_tokens=8192))
    assert str(v.net.seen[-1].url) == vertex_url(MODEL)
    sent = v.net.sent()
    assert sent["generationConfig"]["maxOutputTokens"] == 2048
    assert sent["tools"] == [body()["tools"][0]]


@pytest.mark.parametrize("env", [
    {},
    {"GOOGLE_SA_JSON": SA_JSON, "VERTEX_LOCATION": "global"},
    {"VERTEX_PROJECT": PROJECT},
], ids=["nothing", "credentials-without-project", "project-without-credentials"])
def test_without_the_vertex_env_the_old_url_and_key_stay(tmp_path, monkeypatch, env):
    w = _boot(tmp_path, monkeypatch, vertex=env)
    w.brain._vertex_credentials = FakeCredentials
    assert w.ask().status_code == 200
    [sent] = w.net.seen
    assert str(sent.url) == GOOGLE
    assert sent.headers["x-goog-api-key"] == KEY
    assert "authorization" not in sent.headers


@pytest.mark.parametrize("status", [401, 403, 404, 429, 503])
def test_the_operator_sees_vertexs_reason_but_never_a_bearer_token(v, caplog, status):
    v.net.reply = lambda req: httpx.Response(status, json={"error": {
        "status": "UNAVAILABLE", "message": f"Overloaded. {req.headers['authorization']} {OTHER_TOKEN} and {OTHER_KEY}"}})
    caplog.set_level(logging.DEBUG)
    res = v.ask()
    assert res.status_code == (502 if status in (401, 403, 503) else status)
    assert set(res.json()) == {"error"}
    lines = [r.getMessage() for r in caplog.records if r.levelno >= logging.WARNING and f"status={status}" in r.getMessage()]
    assert lines and "UNAVAILABLE" in lines[0] and "Overloaded" in lines[0]
    for secret in (TOKEN, "ya29.", "AIzaSy", KEY):
        assert secret not in caplog.text and secret not in res.text


@pytest.mark.parametrize("phone, vertex", [
    ("gemini-3.8-flash", "gemini-3.8-flash"),
    ("gemini-3.7-flash", "gemini-3.7-flash"),
    ("gemini-3.1-flash-lite", "gemini-3.1-flash-lite"),
    ("gemini-flash-latest", "gemini-3.8-flash"),
    ("gemini-flash-lite-latest", "gemini-3.5-flash-lite"),
])
def test_the_name_a_profile_maps_to_becomes_the_model_vertex_serves(v, monkeypatch, phone, vertex):
    monkeypatch.setenv("BRAIN_PROFILES", json.dumps({"act": phone}))
    assert v.ask().status_code == 200
    assert str(v.net.seen[-1].url) == vertex_url(vertex)


def test_every_phone_model_and_only_those_has_a_vertex_model(v):
    assert set(v.brain.VERTEX_MODEL_MAP) == set(v.brain.MODELS)
    assert all("flash" in m for m in v.brain.VERTEX_MODEL_MAP.values())


@pytest.mark.parametrize("model", ["gemini-3.8-pro", "gemini-3.5-flash-lite"])
def test_a_model_the_phone_may_not_ask_for_never_reaches_vertex(v, model):
    res = v.ask(path=f"/api/brain/v1beta/models/{model}:streamGenerateContent?alt=sse")
    assert res.status_code == 404
    assert v.net.seen == [] and v.creds.on_loop == []


def test_the_token_is_fetched_once_and_reused(v):
    for _ in range(3):
        assert v.ask().status_code == 200
    assert len(v.creds.on_loop) == 1
    assert {r.headers["authorization"] for r in v.net.seen} == {f"Bearer {TOKEN}1"}


def test_a_token_close_to_expiry_is_refreshed_before_it_is_used(tmp_path, monkeypatch):
    v = _boot_vertex(tmp_path, monkeypatch, creds=FakeCredentials(ttl=60))
    assert v.ask().status_code == 200
    assert v.ask().status_code == 200
    assert len(v.creds.on_loop) == 2
    assert v.net.seen[-1].headers["authorization"] == f"Bearer {TOKEN}2"


def test_the_token_is_refreshed_off_the_event_loop(v):
    v.ask()
    assert v.creds.on_loop == [False]


def test_vertex_alone_turns_the_brain_on(tmp_path, monkeypatch):
    v = _boot_vertex(tmp_path, monkeypatch, key="")
    said = v.client.get("/api/brain/status").json()
    assert said["brain"] is True and "reason" not in said
    assert v.ask().status_code == 200
    assert str(v.net.seen[-1].url) == vertex_url(MODEL)


def test_a_regional_location_uses_its_regional_host(tmp_path, monkeypatch):
    v = _boot_vertex(tmp_path, monkeypatch, VERTEX_LOCATION="us-central1")
    v.ask()
    assert str(v.net.seen[-1].url) == vertex_url(MODEL, "us-central1", "us-central1-aiplatform.googleapis.com")


def test_no_token_is_a_short_502_logged_without_secrets_and_it_costs_the_device_nothing(tmp_path, monkeypatch, caplog):
    creds = FakeCredentials(fail=RuntimeError(f"invalid_grant: Invalid JWT Signature. {PEM} {TOKEN}"))
    v = _boot_vertex(tmp_path, monkeypatch, creds=creds, daily="1")
    caplog.set_level(logging.DEBUG)
    first = v.ask()
    assert first.status_code == 502 and first.json() == {"error": "upstream"}
    assert v.ask().status_code == 502  # не локальний 429 daily_limit
    assert v.net.seen == []
    assert "invalid_grant" in caplog.text
    for secret in ("MIIEv", "PRIVATE KEY", TOKEN, "ya29."):
        assert secret not in caplog.text


def test_a_missing_key_file_is_logged_without_its_path(tmp_path, monkeypatch, caplog):
    where = str(tmp_path / "vm-only" / "vertex-sa.json")
    v = _boot(tmp_path, monkeypatch, key="",
              vertex={"VERTEX_PROJECT": PROJECT, "GOOGLE_APPLICATION_CREDENTIALS": where})
    caplog.set_level(logging.DEBUG)
    assert v.ask().status_code == 502
    assert "Vertex token failed" in caplog.text and "FileNotFoundError" in caplog.text
    assert "vm-only" not in caplog.text and "vertex-sa.json" not in caplog.text


@pytest.mark.parametrize("location", ["evil.example#", "evil.example/x?", "user@evil.example"])
def test_a_location_that_is_not_a_plain_region_never_becomes_the_host_the_token_goes_to(tmp_path, monkeypatch, location):
    v = _boot_vertex(tmp_path, monkeypatch, VERTEX_LOCATION=location)
    v.ask()
    [sent] = v.net.seen
    assert sent.url.host != "evil.example" and "authorization" not in sent.headers
    assert v.creds.on_loop == []


def thinking_sent(w, profile=None, **config):
    doc = body()
    doc["generationConfig"].update(config)
    if profile:
        doc["profile"] = profile
    assert w.ask(doc).status_code == 200
    return w.net.sent()["generationConfig"].get("thinkingConfig")


def profiles(monkeypatch, **chosen):
    monkeypatch.setenv("BRAIN_PROFILES", json.dumps(chosen))


@pytest.mark.parametrize("model", ["gemini-3.8-flash", "gemini-3.7-flash", "gemini-flash-latest"])
def test_a_thinking_model_gets_the_lowest_thinking_vertex_accepts_when_the_phone_sends_none(tmp_path, monkeypatch, model):
    w = _boot(tmp_path, monkeypatch)
    profiles(monkeypatch, act=model)
    assert thinking_sent(w) == {"thinkingLevel": "LOW"}


@pytest.mark.parametrize("model", ["gemini-3.1-flash-lite", "gemini-flash-lite-latest", "gemini-3.5-flash-lite"])
def test_a_lite_model_is_left_alone(tmp_path, monkeypatch, model):
    w = _boot(tmp_path, monkeypatch)
    profiles(monkeypatch, act=model)
    assert thinking_sent(w) is None


def test_the_same_default_rides_to_vertex(v):
    assert thinking_sent(v) == {"thinkingLevel": "LOW"}
    assert str(v.net.seen[-1].url) == vertex_url(MODEL)


@pytest.mark.parametrize("asked, kept", [
    ({"thinkingLevel": "HIGH"}, {"thinkingLevel": "HIGH"}),
    ({"thinkingLevel": "medium"}, {"thinkingLevel": "MEDIUM"}),
    ({"thinkingBudget": 0}, {"thinkingBudget": 0}),
    ({"thinkingBudget": 512}, {"thinkingBudget": 512}),
    ({"thinkingBudget": 1_000_000}, {"thinkingBudget": 2048}),
    ({"thinkingLevel": "LOW", "thinkingBudget": 512}, {"thinkingLevel": "LOW"}),
    ({"thinkingLevel": "LOW", "includeThoughts": True, "x": 1}, {"thinkingLevel": "LOW"}),
])
def test_the_phones_own_thinking_is_kept_within_the_output_cap(w, asked, kept):
    assert thinking_sent(w, thinkingConfig=asked) == kept
    assert thinking_sent(w, "chat", thinkingConfig=asked) == kept


@pytest.mark.parametrize("asked", [
    {"thinkingBudget": -1}, {"thinkingBudget": True}, {"thinkingBudget": "9"}, {"thinkingLevel": "ultra"},
    {"thinkingLevel": 3}, {}, "high", 5, None, [{"thinkingLevel": "HIGH"}],
])
def test_thinking_the_phone_got_wrong_is_dropped_and_the_default_stands(w, asked):
    assert thinking_sent(w, thinkingConfig=asked) == {"thinkingLevel": "LOW"}
    assert thinking_sent(w, "chat", thinkingConfig=asked) is None


def test_a_snake_case_thinking_config_does_not_ride_along(w):
    assert thinking_sent(w, thinking_config={"thinking_budget": 99999}) == {"thinkingLevel": "LOW"}
    assert "thinking_config" not in w.net.sent()["generationConfig"]


@pytest.mark.parametrize("setting, sent", [
    ("MEDIUM", {"thinkingLevel": "MEDIUM"}),
    (" low ", {"thinkingLevel": "LOW"}),
    ("0", {"thinkingBudget": 0}),
    ("512", {"thinkingBudget": 512}),
    ("999999", {"thinkingBudget": 2048}),
    ("", None),
    ("   ", None),
    ("loww", None),
    ("-1", None),
    ("١٢", None),
], ids=repr)
def test_the_default_is_tunable_and_empty_means_none(tmp_path, monkeypatch, setting, sent):
    w = _boot(tmp_path, monkeypatch)
    monkeypatch.setenv("BRAIN_THINKING_DEFAULT", setting)
    assert thinking_sent(w) == sent
    assert thinking_sent(w, "chat") is None


def test_the_phone_still_wins_over_a_tuned_default(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch)
    monkeypatch.setenv("BRAIN_THINKING_DEFAULT", "")
    assert thinking_sent(w, thinkingConfig={"thinkingLevel": "HIGH"}) == {"thinkingLevel": "HIGH"}


LITE = "gemini-3.1-flash-lite"
LITE_ASKS = ("gemini-3.1-flash-lite", "gemini-flash-lite-latest")


def no_tools():
    doc = body()
    del doc["tools"]
    return doc


def served(w, doc=None, model=MODEL, **headers):
    path = f"/api/brain/v1beta/models/{model}:streamGenerateContent?alt=sse"
    res = w.client.post(path, content=json.dumps(doc if doc is not None else body()).encode(),
                        headers={"fly-client-ip": "203.0.113.7", "content-type": "application/json",
                                 "x-nightpost-device": DEVICE, **headers})
    assert res.status_code == 200, res.text
    return str(w.net.seen[-1].url).split("/models/")[1].split(":")[0]


@pytest.mark.parametrize("profile, model", [
    ("chat", "gemini-3.1-flash-lite"), ("act", "gemini-3.8-flash"), ("voice", "gemini-3.1-flash-lite"),
    ("summary", "gemini-flash-lite-latest"),
])
def test_a_profile_in_the_body_picks_the_model_the_server_maps_it_to(w, profile, model):
    assert served(w, dict(body(), profile=profile)) == model


@pytest.mark.parametrize("profile, model", [("chat", LITE), ("act", MODEL)])
def test_a_profile_in_the_header_does_the_same(w, profile, model):
    assert served(w, body(), **{"x-nightpost-profile": profile}) == model
    assert served(w, no_tools(), **{"x-nightpost-profile": profile.upper()}) == model


def test_the_body_wins_over_the_header(w):
    assert served(w, dict(body(), profile="chat"), **{"x-nightpost-profile": "act"}) == LITE


def test_the_profile_field_never_reaches_google(w):
    served(w, dict(body(), profile="chat"))
    assert "profile" not in w.net.sent()


def test_an_ask_with_functions_and_no_profile_is_act(w):
    assert served(w, body(), model="gemini-3.1-flash-lite") == MODEL
    assert w.net.sent()["tools"] == [body()["tools"][0]]


def test_an_ask_without_functions_and_no_profile_is_chat(w):
    assert served(w, no_tools()) == LITE
    assert w.net.sent()["contents"][0]["parts"][0]["text"] == PROMPT


def test_google_search_alone_is_no_tools_because_the_server_strips_it(w):
    doc = no_tools()
    doc["tools"] = [{"google_search": {}}]
    assert served(w, doc) == LITE
    assert "tools" not in w.net.sent()


@pytest.mark.parametrize("tools", [[], None], ids=["empty-list", "null"])
def test_an_empty_tools_member_is_no_tools(w, tools):
    doc = no_tools()
    doc["tools"] = tools
    assert served(w, doc) == LITE


def test_functions_next_to_google_search_are_act(w):
    doc = no_tools()
    doc["tools"] = [{"google_search": {}}, {"functionDeclarations": body()["tools"][0]["functionDeclarations"]}]
    assert served(w, doc) == MODEL


@pytest.mark.parametrize("profile", ["", "CHAT-ish", "gemini-3.8-flash", 7, None, ["act"], {"act": 1}, "  "])
def test_an_unknown_profile_is_inferred_from_the_tools_not_refused(w, profile):
    assert served(w, dict(no_tools(), profile=profile)) == LITE
    assert served(w, dict(body(), profile=profile)) == MODEL
    assert "profile" not in w.net.sent()


def test_a_bad_body_profile_falls_to_a_good_header_one(w):
    assert served(w, dict(no_tools(), profile="nonsense"), **{"x-nightpost-profile": "act"}) == MODEL


@pytest.mark.parametrize("asked", [*LITE_ASKS, "gemini-3.8-flash", "gemini-3.7-flash", "gemini-flash-latest"])
def test_the_model_in_the_address_is_advisory_only(w, asked):
    assert served(w, dict(no_tools(), profile="chat"), model=asked) == LITE
    assert served(w, dict(no_tools(), profile="act"), model=asked) == MODEL


def test_an_old_phone_asking_the_big_model_without_functions_gets_the_chat_model(w):
    assert served(w, no_tools(), model="gemini-3.8-flash") == LITE


def test_a_model_the_phone_may_not_ask_for_is_still_404_and_never_mapped(w):
    res = w.ask(dict(no_tools(), profile="chat"), path="/api/brain/v1beta/models/gemini-9-ultra:streamGenerateContent?alt=sse")
    assert res.status_code == 404 and w.net.seen == []


def test_the_chat_profile_rides_to_vertex_as_the_vertex_model(v):
    assert served(v, no_tools(), model="gemini-flash-latest") == LITE
    assert str(v.net.seen[-1].url) == vertex_url(LITE)
    served(v, dict(no_tools(), profile="summary"))
    assert str(v.net.seen[-1].url) == vertex_url("gemini-3.5-flash-lite")


def test_an_env_override_replaces_only_the_profiles_it_names(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch)
    profiles(monkeypatch, chat="gemini-3.7-flash", summary="gemini-3.5-flash-lite")
    assert served(w, no_tools()) == "gemini-3.7-flash"
    assert w.net.sent()["generationConfig"]["thinkingConfig"] == {"thinkingLevel": "LOW"}
    assert served(w, dict(no_tools(), profile="act")) == MODEL
    assert served(w, dict(no_tools(), profile="voice")) == LITE
    assert served(w, dict(no_tools(), profile="summary")) == "gemini-3.5-flash-lite"


def test_the_env_override_is_read_per_ask_not_at_boot(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch)
    assert served(w, no_tools()) == LITE
    profiles(monkeypatch, chat="gemini-3.8-flash")
    assert served(w, no_tools()) == "gemini-3.8-flash"
    monkeypatch.delenv("BRAIN_PROFILES")
    assert served(w, no_tools()) == LITE


@pytest.mark.parametrize("bad", ["{not json", "[1, 2]", '"chat"', "42", "null"])
def test_broken_env_json_falls_back_to_the_defaults_with_one_warning(tmp_path, monkeypatch, caplog, bad):
    w = _boot(tmp_path, monkeypatch)
    monkeypatch.setenv("BRAIN_PROFILES", bad)
    caplog.set_level(logging.WARNING)
    assert served(w, no_tools()) == LITE
    assert served(w, body()) == MODEL
    assert w.client.get("/api/brain/status").json()["profiles"] == w.brain.PROFILE_DEFAULTS
    warned = [r for r in caplog.records if "BRAIN_PROFILES" in r.getMessage()]
    assert len(warned) == 1 and warned[0].levelno == logging.WARNING


def test_an_env_entry_naming_an_unknown_model_or_profile_is_skipped_with_a_warning(tmp_path, monkeypatch, caplog):
    w = _boot(tmp_path, monkeypatch)
    monkeypatch.setenv("BRAIN_PROFILES", json.dumps(
        {"chat": "gemini-9-ultra", "act": 5, "music": "gemini-3.7-flash", "voice": "gemini-3.7-flash"}))
    caplog.set_level(logging.WARNING)
    mapped = w.client.get("/api/brain/status").json()["profiles"]
    assert mapped == dict(w.brain.PROFILE_DEFAULTS, voice="gemini-3.7-flash")
    warned = [r.getMessage() for r in caplog.records if "BRAIN_PROFILES" in r.getMessage()]
    assert len(warned) == 3
    assert not any("ultra" in line for line in warned)


def test_the_status_shows_the_profile_map_and_no_secret(w):
    said = w.client.get("/api/brain/status").json()
    assert said["profiles"] == {"chat": LITE, "act": MODEL, "voice": LITE, "summary": "gemini-flash-lite-latest"}
    assert KEY not in json.dumps(said)
    assert said["models"] == list(w.brain.MODELS)


def test_the_status_follows_the_env(tmp_path, monkeypatch):
    w = _boot(tmp_path, monkeypatch)
    profiles(monkeypatch, act="gemini-3.7-flash")
    assert w.client.get("/api/brain/status").json()["profiles"]["act"] == "gemini-3.7-flash"


def test_the_chat_model_is_not_given_thinking_but_keeps_the_phones_own(w):
    assert served(w, no_tools()) == LITE
    assert "thinkingConfig" not in w.net.sent()["generationConfig"]
    doc = no_tools()
    doc["generationConfig"]["thinkingConfig"] = {"thinkingLevel": "HIGH"}
    served(w, doc)
    assert w.net.sent()["generationConfig"]["thinkingConfig"] == {"thinkingLevel": "HIGH"}


def test_the_ask_line_names_profile_served_and_asked(w, caplog):
    caplog.set_level(logging.WARNING)
    served(w, no_tools())
    served(w, body(), model="gemini-3.7-flash")
    lines = [ASK_LINE.fullmatch(r.getMessage()) for r in asks(caplog)]
    assert [(m["profile"], m["model"], m["asked"]) for m in lines] == [
        ("chat", LITE, MODEL), ("act", MODEL, "gemini-3.7-flash")]


def test_a_real_vertex_name_in_the_env_is_logged_by_name_not_as_unknown(tmp_path, monkeypatch, caplog):
    w = _boot(tmp_path, monkeypatch)
    profiles(monkeypatch, chat="gemini-3.5-flash-lite")
    caplog.set_level(logging.WARNING)
    served(w, no_tools())
    assert ASK_LINE.fullmatch(asks(caplog)[0].getMessage())["model"] == "gemini-3.5-flash-lite"


def test_a_refusal_after_the_mapping_still_names_profile_and_both_models(w, caplog):
    caplog.set_level(logging.WARNING)
    w.net.reply = lambda req: httpx.Response(500, json={"error": {"status": "INTERNAL", "message": "x"}})
    assert w.ask(no_tools()).status_code == 500
    [line] = [ASK_LINE.fullmatch(r.getMessage()) for r in asks(caplog)]
    assert (line["profile"], line["model"], line["asked"], line["status"]) == ("chat", LITE, MODEL, "500")


def test_a_refusal_before_the_body_is_read_has_no_profile_yet(w, caplog):
    caplog.set_level(logging.WARNING)
    w.ask(device="bad")
    [line] = [ASK_LINE.fullmatch(r.getMessage()) for r in asks(caplog)]
    assert (line["profile"], line["status"]) == ("-", "400")
