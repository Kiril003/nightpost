"""Кабінет показує про пристрій і ліцензію лише те, що сервер справді знає.

«Остання активність» — це час останньої УСПІШНОЇ підписаної звірки
(/api/validate зі статусом active), а не будь-якого запиту. «Звідки ліцензія» —
провайдер із запису ліцензії, з якого кабінет складає історію покупок.
"""
from test_accounts import _boot, _head, _issue, _login

FP = "a" * 64


def test_активність_пристрою_з_підписаної_звірки(tmp_path, monkeypatch):
    client, sent = _boot(tmp_path, monkeypatch)
    issued = _issue(client, "kira@example.com")
    cert = client.post(
        "/api/activate",
        json={"license_key": issued["license_key"], "device_fingerprint": FP, "device_name": "Pixel 8"},
    ).json()["certificate"]
    token = _login(client, sent, "kira@example.com")

    device = client.get("/api/me", headers=_head(token)).json()["licenses"][0]["devices"][0]
    # До першої звірки сервер нічого не знає — і не вигадує.
    assert device["last_seen_at"] is None

    # Звірка з ЧУЖИМ відбитком не оновлює активність цього пристрою.
    client.post("/api/validate", json={"license_id": cert["license_id"], "cert_serial": cert["serial"],
                                       "device_fingerprint": "b" * 64})
    device = client.get("/api/me", headers=_head(token)).json()["licenses"][0]["devices"][0]
    assert device["last_seen_at"] is None

    client.post("/api/validate", json={"license_id": cert["license_id"], "cert_serial": cert["serial"],
                                       "device_fingerprint": FP})
    device = client.get("/api/me", headers=_head(token)).json()["licenses"][0]["devices"][0]
    assert device["last_seen_at"] and device["last_seen_at"] >= device["activated_at"]


def test_ліцензія_каже_звідки_вона_і_чому_відкликана(tmp_path, monkeypatch):
    client, sent = _boot(tmp_path, monkeypatch)
    issued = _issue(client, "kira@example.com")
    token = _login(client, sent, "kira@example.com")
    lic = client.get("/api/me", headers=_head(token)).json()["licenses"][0]
    assert lic["provider"] == "manual"
    assert lic["revoked_reason"] is None

    client.post("/admin/revoke", headers={"X-Admin-Token": "test-admin-token"},
                json={"license_id": issued["license_id"], "reason": "refunded"})
    lic = client.get("/api/me", headers=_head(token)).json()["licenses"][0]
    assert lic["revoked"] and lic["revoked_reason"] == "refunded"
