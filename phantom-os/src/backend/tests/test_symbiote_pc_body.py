"""Тіло ПК симбіота: бачити й діяти на комп'ютері власника — відкрито й зі згодою.

Червоне без зміни: у реєстрі агента не було жодного `pc.*` (розум не бачив ні
екрана, ні вікон, ні файлів), `body.pc.*` минали драбину (набір і буфер — лише
журналом), сторожа секретів не існувало, телефон не міг попросити ПК нічого,
крім п'яти дієслів. Усі тести — на підробній ОС (`symbiote.pc.fake`), її
підставляє автофікстура `_pc_body_isolated` у conftest.
"""
from __future__ import annotations

import asyncio
import json
import os
import time
import uuid
from pathlib import Path
from typing import AsyncIterator

import pytest
import pytest_asyncio

os.environ.setdefault("JWT_SECRET_KEY", "test-secret-symbiote-pc-body")

from agent.actions.registry import registry
from agent.schemas import RiskLevel
from symbiote import contract
from symbiote.pc.backend import Element, Window, get_backend


@pytest.fixture(autouse=True)
def _fresh(tmp_path):
    from symbiote.feed import feed
    from symbiote.ladder import pending_asks, stop_switch

    feed.use_path(tmp_path / "feed.jsonl")
    stop_switch.use_path(tmp_path / "stop.json")
    pending_asks.drop_all()
    yield
    pending_asks.drop_all()


@pytest.fixture
def fake():
    return get_backend()


@pytest.fixture
def root(tmp_path) -> Path:
    from symbiote.pc.files import roots

    base = tmp_path / "work"
    base.mkdir()
    (base / "notes.txt").write_text("перший рядок\nдругий рядок\n", encoding="utf-8")
    (base / ".ssh").mkdir()
    (base / ".ssh" / "id_ed25519").write_text("-----BEGIN OPENSSH PRIVATE KEY-----\n")
    (base / ".env").write_text("TOKEN=secret\n")
    roots.add(str(base))
    return base


def _feed(user_id: str) -> list[dict]:
    from symbiote.feed import feed

    return feed.list(user_id, limit=100)


async def _run(user_id: str, verb: str, wait_s: float = 0.0, **args):
    from symbiote.pc.gate import run

    return await run(verb, args, user_id=user_id, task_id=f"t-{uuid.uuid4()}", wait_s=wait_s)


def _grant(**levels: str) -> None:
    from symbiote.pc.gate import mind_ladder

    for sphere, level in levels.items():
        mind_ladder.set(sphere, level)


# ── Контракт і реєстр ───────────────────────────────────────────────────────


def test_every_pc_verb_is_a_typed_tool_with_the_charter_risk():
    names = set(registry.names())
    expect = {"read": RiskLevel.SAFE, "local": RiskLevel.LOW,
              "outward": RiskLevel.MEDIUM, "irreversible": RiskLevel.HIGH}
    catalog = {c["name"]: c for c in registry.catalog()}
    assert contract.PC_VERBS, "контракт тіла ПК порожній"
    for verb, spec in contract.PC_VERBS.items():
        assert verb in names, verb
        assert catalog[verb]["risk_level"] == int(expect[spec["risk"]]), verb
        for key, rule in spec.get("args", {}).items():
            assert catalog[verb]["args"][key]["required"] is bool(rule.get("required")), (verb, key)
    assert catalog["pc.files.delete"]["risk_level"] == int(RiskLevel.HIGH)
    assert catalog["pc.browser.click"]["risk_level"] == int(RiskLevel.MEDIUM)
    assert contract.contract_problems() == []


def test_phone_contract_lists_are_untouched():
    """Телефонна BodyLadderContractTest звіряє `spheres` і `verbs` — тіло ПК
    живе поруч (`pc_body`), щоб телефон не почервонів від чужої зміни."""
    raw = json.loads(contract.CONTRACT_PATH.read_text(encoding="utf-8"))
    assert set(raw["spheres"]) == {"place", "sound", "notices", "clipboard", "screen", "keyboard"}
    # Телефонне тіло тримає свої дієслова в `verbs`; тіло ПК їх не чіпає.
    assert {"phone.locate", "phone.screen.read", "phone.tap", "pc.type", "pc.notify"} <= set(raw["verbs"])
    assert not any(v.startswith("pc.") and v in raw["pc_body"]["verbs"] for v in raw["verbs"])
    assert not set(raw["pc_body"]["verbs"]) & set(raw["verbs"])


def test_every_desktop_verb_has_hands():
    from symbiote import desktop_hands

    for verb, spec in contract.ALL_VERBS.items():
        if spec["body"] == "desktop":
            assert desktop_hands.knows(verb), verb


# ── Драбина розуму на ПК ────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_by_default_the_mind_sees_windows_but_not_the_screen(auth_root_user, fake):
    out = await _run(auth_root_user.id, "pc.see")
    assert not out.ok and out.status == "refused"
    assert ("screenshot", None) not in fake.calls
    assert _feed(auth_root_user.id)[0]["status"] == "refused"

    out = await _run(auth_root_user.id, "pc.windows")
    assert out.ok, out.detail
    assert [w["title"] for w in out.payload["windows"]] == ["Нотатки — main.md", "Mozilla Firefox"]
    rows = _feed(auth_root_user.id)
    assert [r["status"] for r in rows[:2]] == ["done", "sent"]
    assert rows[0]["what"] == "pc.windows" and rows[1]["who"] == "mind"


@pytest.mark.asyncio
async def test_see_goes_through_the_model_router(auth_root_user, fake, monkeypatch):
    from ai import model_router

    seen: list[tuple[bytes, str]] = []

    async def fake_see(image, prompt, **_kw):
        seen.append((image, prompt))
        return model_router.Seen(text="На екрані редактор з main.md", provider="gemini")

    monkeypatch.setattr(model_router, "see", fake_see)
    _grant(sight="see")
    out = await _run(auth_root_user.id, "pc.see", prompt="Що відкрито?")
    assert out.ok, out.detail
    assert out.payload == {"text": "На екрані редактор з main.md", "provider": "gemini", "mode": "describe"}
    assert seen and seen[0][0].startswith(b"\x89PNG") and seen[0][1] == "Що відкрито?"
    # Що бачив — у стрічку не йде, лише що зроблено.
    assert "редактор" not in json.dumps(_feed(auth_root_user.id), ensure_ascii=False)


@pytest.mark.asyncio
async def test_password_manager_on_screen_blocks_capture_and_hides_its_title(auth_root_user, fake, monkeypatch):
    from ai import model_router

    async def must_not(*_a, **_k):
        raise AssertionError("знімок не мав піти в модель")

    monkeypatch.setattr(model_router, "see", must_not)
    fake.wins.append(Window(id="w3", title="Passwords.kdbx - KeePassXC", app="KeePassXC", pid=103))
    _grant(sight="silent")
    out = await _run(auth_root_user.id, "pc.see")
    assert not out.ok and "паролів" in out.detail
    assert ("screenshot", None) not in fake.calls

    listed = (await _run(auth_root_user.id, "pc.windows")).payload["windows"]
    kp = next(w for w in listed if w["id"] == "w3")
    assert kp["secret"] is True and "kdbx" not in kp["title"]

    fake.wins.pop()
    fake.focus = Element(role="password text", name="Пароль", app="firefox")
    out = await _run(auth_root_user.id, "pc.see")
    assert not out.ok and "поле пароля" in out.detail


@pytest.mark.asyncio
async def test_ui_tree_never_carries_password_text(auth_root_user, fake):
    _grant(sight="see")
    out = await _run(auth_root_user.id, "pc.ui.find", app="code")
    assert out.ok
    by_role = {e["role"]: e for e in out.payload["elements"]}
    assert by_role["text"]["text"] == "рядок у редакторі"
    assert by_role["password text"]["text"] is None
    assert "hunter2" not in json.dumps(out.payload, ensure_ascii=False)


# ── Буфер: читання з «так» ──────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_clipboard_read_is_refused_on_see_and_waits_for_yes(auth_root_user, auth_root_client, fake):
    out = await _run(auth_root_user.id, "pc.clipboard.read")
    assert out.status == "refused" and ("clipboard_read", None) not in fake.calls

    _grant(clipboard="ask")
    task = asyncio.ensure_future(_run(auth_root_user.id, "pc.clipboard.read", wait_s=10))
    from symbiote.ladder import pending_asks

    for _ in range(100):
        await asyncio.sleep(0.02)
        if pending_asks._items:
            break
    assert pending_asks._items, "прохання не стало в чергу"
    assert ("clipboard_read", None) not in fake.calls  # без «так» — ані байта
    journal_id = next(iter(pending_asks._items))
    r = auth_root_client.post(f"/api/v1/symbiote/journal/{journal_id}/answer", json={"yes": True})
    assert r.status_code == 200, r.text
    out = await asyncio.wait_for(task, timeout=5)
    assert out.ok and out.status == "done"
    assert out.payload["text"] == "привіт з буфера"
    statuses = [r["status"] for r in _feed(auth_root_user.id)]
    assert "asked" in statuses and "done" in statuses
    assert "привіт з буфера" not in json.dumps(_feed(auth_root_user.id), ensure_ascii=False)


@pytest.mark.asyncio
async def test_clipboard_marked_by_password_manager_is_never_read(auth_root_user, fake):
    from symbiote.pc.backend import Clip

    _grant(clipboard="report")
    fake.clip = Clip(text="hunter2", types=["text/plain", "x-kde-passwordManagerHint"])
    out = await _run(auth_root_user.id, "pc.clipboard.read")
    assert not out.ok and "менеджером паролів" in out.detail and "text" not in out.payload
    fake.clip = Clip(text="ghp_" + "a" * 36, types=["text/plain"])
    out = await _run(auth_root_user.id, "pc.clipboard.read")
    assert not out.ok and "токен" in out.detail


# ── Файли ───────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_files_live_only_in_approved_roots_and_never_touch_secrets(auth_root_user, tmp_path):
    _grant(files="report")
    out = await _run(auth_root_user.id, "pc.files.read", path=str(tmp_path / "x.txt"))
    assert not out.ok and "не схвалив" in out.detail

    from symbiote.pc.files import roots

    work = tmp_path / "work"
    work.mkdir()
    (work / "a.md").write_text("привіт", encoding="utf-8")
    (work / ".ssh").mkdir()
    (work / ".ssh" / "id_rsa").write_text("KEY")
    outside = tmp_path / "outside.txt"
    outside.write_text("чуже")
    (work / "link.txt").symlink_to(outside)
    roots.add(str(work))

    ok = await _run(auth_root_user.id, "pc.files.read", path=str(work / "a.md"))
    assert ok.ok and ok.payload["text"] == "привіт"
    for bad, why in ((work / ".ssh" / "id_rsa", "ключі"), (work / "link.txt", "поза"),
                     (outside, "поза"), (work / ".." / "outside.txt", "поза")):
        out = await _run(auth_root_user.id, "pc.files.read", path=str(bad))
        assert not out.ok and why in out.detail, (bad, out.detail)
    listed = await _run(auth_root_user.id, "pc.files.list", path=str(work))
    assert {e["name"] for e in listed.payload["entries"]} == {"a.md", "link.txt"}
    assert listed.payload["hidden_secret"] == 1
    found = await _run(auth_root_user.id, "pc.files.search", query="прив", content="true")
    assert [Path(r["path"]).name for r in found.payload["results"]] == ["a.md"]


def test_phantom_own_data_can_never_be_a_root(tmp_path, monkeypatch):
    from symbiote.pc.backend import PcRefused
    from symbiote.pc.files import resolve, roots

    data = tmp_path / "phantom-data"
    (data / "symbiote").mkdir(parents=True)
    monkeypatch.setenv("PHANTOM_DATA_DIR", str(data))
    with pytest.raises(PcRefused):
        roots.add(str(data))
    with pytest.raises(PcRefused):
        roots.add(str(tmp_path))  # тека, що містить дані PHANTOM
    work = data.parent / "w"
    work.mkdir()
    roots.add(str(work))
    with pytest.raises(PcRefused):
        resolve(str(data / "symbiote" / "stop.json"))


@pytest.mark.asyncio
async def test_write_keeps_versions_and_move_never_overwrites(auth_root_user, root):
    from symbiote.pc.files import backups

    _grant(files="report")
    target = root / "plan.md"
    out = await _run(auth_root_user.id, "pc.files.write", path=str(target), text="v1")
    assert out.ok and target.read_text() == "v1" and out.payload["backup"] is None
    again = await _run(auth_root_user.id, "pc.files.write", path=str(target), text="v2")
    assert not again.ok and "overwrite" in again.detail
    out = await _run(auth_root_user.id, "pc.files.write", path=str(target), text="v2", mode="overwrite")
    assert out.ok and target.read_text() == "v2" and out.payload["backup"]
    versions = backups.versions(target)
    assert len(versions) == 1
    assert (backups.base() / backups._slot(target).name / versions[0]).read_text() == "v1"
    # «чарс», а не вміст — у журналі й стрічці
    assert "v2" not in json.dumps(_feed(auth_root_user.id), ensure_ascii=False)

    out = await _run(auth_root_user.id, "pc.files.move", src=str(target), dst=str(root / "notes.txt"))
    assert not out.ok and (root / "notes.txt").read_text().startswith("перший")
    out = await _run(auth_root_user.id, "pc.files.move", src=str(target), dst=str(root / "plans" / "p.md"))
    assert out.ok and (root / "plans" / "p.md").read_text() == "v2"


@pytest.mark.asyncio
async def test_delete_always_asks_even_on_silent_and_keeps_a_copy(auth_root_user, auth_root_client, root):
    from symbiote.ladder import pending_asks
    from symbiote.pc.files import backups
    from symbiote.pc.gate import mind_ladder

    assert mind_ladder.set("erase", "silent") == "ask"  # незворотне вище «так» не піднімається
    victim = root / "notes.txt"
    out = await _run(auth_root_user.id, "pc.files.delete", path=str(victim))
    assert out.status == "asked" and victim.exists()
    journal_id = out.journal_id
    assert pending_asks.has(journal_id)
    r = auth_root_client.post(f"/api/v1/symbiote/journal/{journal_id}/answer", json={"yes": True})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "done"
    assert not victim.exists()
    assert len(backups.versions(victim)) == 1


# ── Оболонка ────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_shell_runs_allowlisted_commands_in_roots_and_journals_each(auth_root_user, root):
    _grant(shell="report")
    out = await _run(auth_root_user.id, "pc.shell", cmd="ls -a", cwd=str(root))
    assert out.ok, out.detail
    assert "notes.txt" in out.payload["output"]
    if out.payload["sandbox"] == "bwrap":
        # секретні теки всередині кореня закриті порожнім tmpfs
        listing = await _run(auth_root_user.id, "pc.shell", cmd="ls -a .ssh", cwd=str(root))
        assert listing.ok is False or "id_ed25519" not in listing.payload.get("output", "")
    top = _feed(auth_root_user.id)
    assert any(r["what"] == "pc.shell" and "$ ls -a" in r["detail"] for r in top)

    refused = {
        "sudo ls": "прав", "nmcli radio wifi off": "мережев", "curl https://x.y": "мережев",
        "rm -rf notes.txt": "білому списку", "find . -delete": "-delete", "ls | cat": "конвеєр",
        "cat ~/.ssh/id_rsa": "поза", f"cat {root}/.env": "секрет", "git -c core.pager=sh status": "-c",
        "git push": "лише читати", "/bin/ls": "без шляху", "rg --pre=sh x": "--pre",
    }
    for cmd, why in refused.items():
        out = await _run(auth_root_user.id, "pc.shell", cmd=cmd, cwd=str(root))
        assert not out.ok and why in out.detail, (cmd, out.detail)


@pytest.mark.asyncio
async def test_stop_all_kills_a_running_command(auth_root_user, root):
    from symbiote.ladder import stop_switch

    _grant(shell="report")
    t0 = time.monotonic()
    task = asyncio.ensure_future(_run(auth_root_user.id, "pc.shell", cmd="tail -f notes.txt", cwd=str(root)))
    await asyncio.sleep(0.6)
    stop_switch.set(True)
    out = await asyncio.wait_for(task, timeout=8)
    assert not out.ok and "Стоп усе" in out.detail
    assert time.monotonic() - t0 < 6


@pytest.mark.asyncio
async def test_stop_all_refuses_every_pc_verb(auth_root_user, fake):
    from symbiote.ladder import stop_switch
    from symbiote.pc.gate import mind_ladder

    mind_ladder.preset("full")
    stop_switch.set(True)
    sample = {"path": "x", "query": "x", "app": "x", "keys": "ctrl+s", "label": "x", "src": "x",
              "dst": "y", "cmd": "ls", "url": "https://a.b", "ref": "1", "text": "t"}
    for verb, spec in contract.PC_VERBS.items():
        args = {k: sample[k] for k, rule in spec.get("args", {}).items() if rule.get("required")}
        for group in spec.get("one_of", []):
            args.setdefault(group[0], sample[group[0]])
        out = await _run(auth_root_user.id, verb, **args)
        assert not out.ok and out.status == "stopped", (verb, out.status, out.detail)
    assert fake.calls == []


# ── Клавіатура, миша, програми ──────────────────────────────────────────────


@pytest.mark.asyncio
async def test_typing_and_keys_never_go_into_a_password_manager(auth_root_user, fake):
    from symbiote import desktop_hands

    fake.wins[0] = Window(id="w1", title="Unlock Database - KeePassXC", app="KeePassXC", pid=9, focused=True)
    ok, detail, _ = await desktop_hands.execute("pc.type", {"text": "секрет"})
    assert not ok and "паролів" in detail
    _grant(keyboard="ask")
    ok, detail, _ = await desktop_hands.execute("pc.hotkey", {"keys": "ctrl+v"})
    assert not ok and "паролів" in detail
    assert not [c for c in fake.calls if c[0] in ("type", "hotkey")]

    fake.wins[0] = Window(id="w1", title="main.md", app="code", pid=101, focused=True)
    fake.focus = Element(role="password text", name="Пароль", app="code")
    ok, detail, _ = await desktop_hands.execute("pc.hotkey", {"keys": "ctrl+v"})
    assert not ok and "поле пароля" in detail
    fake.focus = None
    ok, detail, _ = await desktop_hands.execute("pc.hotkey", {"keys": "ctrl+s"})
    assert ok and ("hotkey", "ctrl+s") in fake.calls


@pytest.mark.asyncio
async def test_click_by_accessibility_and_never_into_a_password_field(auth_root_user, fake):
    _grant(pointer="report")
    out = await _run(auth_root_user.id, "pc.click", label="Зберегти")
    assert out.ok and out.payload["how"] == "accessibility"
    assert ("ui_press", "Зберегти") in fake.calls
    out = await _run(auth_root_user.id, "pc.click", label="Пароль")
    assert not out.ok and "пароля" in out.detail
    out = await _run(auth_root_user.id, "pc.click", x="10", y="20")
    assert out.ok and ("click", (10, 20, "left")) in fake.calls


@pytest.mark.asyncio
async def test_open_apps_but_not_password_managers(auth_root_user, fake):
    _grant(windows="report")
    out = await _run(auth_root_user.id, "pc.app.open", app="kitty")
    assert out.ok and ("launch_app", "kitty") in fake.calls
    out = await _run(auth_root_user.id, "pc.app.open", app="keepassxc")
    assert not out.ok and "власник" in out.detail
    out = await _run(auth_root_user.id, "pc.app.open", app="/usr/bin/rm")
    assert not out.ok


# ── Браузер ─────────────────────────────────────────────────────────────────


class FakeCdp:
    """Сторінка магазину з формою входу: пошук, кнопка «Купити», пароль."""

    NODES = {
        11: {"tag": "input", "type": "search", "name": "q", "text": "", "label": "Пошук"},
        12: {"tag": "button", "type": "submit", "text": "Купити зараз", "formHasPassword": False},
        13: {"tag": "input", "type": "password", "name": "password", "text": "", "formHasPassword": True},
        14: {"tag": "input", "type": "text", "name": "login", "text": "", "formHasPassword": True},
        15: {"tag": "a", "type": "", "text": "Доставка", "href": "https://shop.test/delivery"},
    }

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return None

    async def send(self, method, params=None):
        params = params or {}
        self.calls.append((method, params))
        if method == "DOM.getDocument":
            return {"root": {"nodeName": "#document", "children": [
                {"nodeName": "INPUT", "backendNodeId": 13, "attributes": ["type", "password", "name", "password"]},
                {"nodeName": "INPUT", "backendNodeId": 11, "attributes": ["type", "search"]},
            ]}}
        if method == "Accessibility.getFullAXTree":
            return {"nodes": [
                {"role": {"value": "heading"}, "name": {"value": "Магазин"}},
                {"role": {"value": "searchbox"}, "name": {"value": "Пошук"}, "backendDOMNodeId": 11,
                 "value": {"value": "кава"}},
                {"role": {"value": "button"}, "name": {"value": "Купити зараз"}, "backendDOMNodeId": 12},
                {"role": {"value": "textbox"}, "name": {"value": "Пароль"}, "backendDOMNodeId": 13,
                 "value": {"value": "hunter2"}},
                {"role": {"value": "link"}, "name": {"value": "Доставка"}, "backendDOMNodeId": 15},
            ]}
        if method == "Runtime.evaluate":
            return {"result": {"value": json.dumps({"title": "Магазин", "url": "https://shop.test/"})}}
        if method == "DOM.resolveNode":
            return {"object": {"objectId": f"obj-{params['backendNodeId']}"}}
        if method == "Runtime.callFunctionOn":
            node = int(params["objectId"].split("-")[1])
            if "JSON.stringify" in params["functionDeclaration"]:
                return {"result": {"value": json.dumps(self.NODES[node])}}
            return {"result": {"value": True}}
        return {}


@pytest.fixture
def cdp():
    from symbiote.pc.browser import browser

    page = FakeCdp()
    browser.transport_factory = lambda: page
    yield page
    browser.transport_factory = None


def _acted(page: FakeCdp) -> list[int]:
    return [int(p["objectId"].split("-")[1]) for m, p in page.calls
            if m == "Runtime.callFunctionOn" and "JSON.stringify" not in p["functionDeclaration"]]


@pytest.mark.asyncio
async def test_browser_reads_the_page_without_passwords_and_refuses_buy_and_login(auth_root_user, cdp):
    _grant(browser="report")
    out = await _run(auth_root_user.id, "pc.browser.read")
    assert out.ok, out.detail
    text = "\n".join(out.payload["lines"])
    assert "[11] searchbox «Пошук» = кава" in text and "[15] link «Доставка»" in text
    assert "hunter2" not in json.dumps(out.payload, ensure_ascii=False)
    assert out.payload["login_form"] is True

    buy = await _run(auth_root_user.id, "pc.browser.click", ref="12")
    assert not buy.ok and "покупки" in buy.detail
    pw = await _run(auth_root_user.id, "pc.browser.fill", ref="13", text="hunter2")
    assert not pw.ok and pw.status == "refused" and "пароля" in pw.detail
    login = await _run(auth_root_user.id, "pc.browser.fill", ref="14", text="kyrylo")
    assert not login.ok and "входу" in login.detail
    assert _acted(cdp) == []

    ok = await _run(auth_root_user.id, "pc.browser.fill", ref="11", text="чай")
    assert ok.ok, ok.detail
    ok = await _run(auth_root_user.id, "pc.browser.click", ref="15")
    assert ok.ok, ok.detail
    assert _acted(cdp) == [11, 15]


def test_browser_uses_its_own_profile_only(tmp_path, monkeypatch):
    from symbiote.pc.browser import launch_argv, profile_dir

    monkeypatch.setenv("PHANTOM_DATA_DIR", str(tmp_path))
    prof = profile_dir()
    assert prof == tmp_path / "symbiote" / "browser-profile"
    argv = launch_argv("/usr/bin/chromium", prof)
    assert f"--user-data-dir={prof}" in argv
    assert "--remote-debugging-address=127.0.0.1" in argv
    assert not any(a.startswith("--profile-directory") for a in argv)


@pytest.mark.asyncio
async def test_browser_opens_only_web_addresses(auth_root_user, cdp):
    _grant(browser="report")
    for bad in ("file:///etc/passwd", "javascript:alert(1)", "chrome://settings"):
        out = await _run(auth_root_user.id, "pc.browser.open", url=bad)
        assert not out.ok and "http" in out.detail
    out = await _run(auth_root_user.id, "pc.browser.open", url="https://shop.test/")
    assert out.ok and ("Page.navigate", {"url": "https://shop.test/"}) in cdp.calls


# ── Телефон просить ПК ──────────────────────────────────────────────────────


@pytest_asyncio.fixture
async def phone(auth_root_user) -> AsyncIterator[dict]:
    from db.database import AsyncSessionLocal
    from db.models import PairedDevice
    from security.device_token import create_device_token

    async with AsyncSessionLocal() as s:
        row = PairedDevice(
            user_id=auth_root_user.id, device_name="Телефон Кирила", device_model="samsung/a52",
            platform="android", device_pub_ed25519="L" * 44, capabilities_json='["approvals", "sensors"]',
        )
        s.add(row)
        await s.commit()
        await s.refresh(row)
    token, _ = create_device_token(device_id=row.id, user_id=auth_root_user.id)
    yield {"id": row.id, "auth": {"Authorization": f"Bearer {token}"}}


def test_phone_asks_the_pc_body_through_its_own_ladder(auth_root_client, phone, root):
    ask = {"verb": "pc.files.read", "args": {"path": str(root / "notes.txt")}}
    r = auth_root_client.post("/api/v1/symbiote/command", json=ask, headers=phone["auth"])
    assert r.status_code == 403, r.text
    assert "вимкнено" in r.json()["detail"]["message"]

    r = auth_root_client.patch("/api/v1/symbiote/ladder",
                               json={"device_id": phone["id"], "sphere": "files", "level": "see"})
    assert r.status_code == 200, r.text
    r = auth_root_client.post("/api/v1/symbiote/command", json=ask, headers=phone["auth"])
    assert r.status_code == 200, r.text
    result = r.json()["result"]
    assert result["ok"] and result["payload"]["text"].startswith("перший рядок")
    # але й телефону сторож не віддає секретів
    r = auth_root_client.post("/api/v1/symbiote/command", headers=phone["auth"],
                              json={"verb": "pc.files.read", "args": {"path": str(root / ".env")}})
    assert r.status_code == 200 and r.json()["result"]["ok"] is False


def test_pc_routes_let_only_the_human_change_the_minds_rights(auth_root_client, phone, tmp_path):
    r = auth_root_client.get("/api/v1/symbiote/pc")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["backend"] == "fake"
    assert {s["sphere"]: s["level"] for s in body["ladder"]}["files"] == "off"

    r = auth_root_client.patch("/api/v1/symbiote/pc/ladder", json={"sphere": "files", "level": "silent"},
                               headers=phone["auth"])
    assert r.status_code == 403
    r = auth_root_client.post("/api/v1/symbiote/pc/roots", json={"path": str(tmp_path)}, headers=phone["auth"])
    assert r.status_code == 403

    r = auth_root_client.patch("/api/v1/symbiote/pc/ladder", json={"sphere": "files", "level": "report"})
    assert r.status_code == 200
    r = auth_root_client.post("/api/v1/symbiote/pc/ladder/preset", json={"preset": "full"})
    levels = {s["sphere"]: s["level"] for s in r.json()["ladder"]}
    assert levels["files"] == "report" and levels["erase"] == "ask" and levels["keyboard"] == "ask"
    r = auth_root_client.post("/api/v1/symbiote/pc/roots", json={"path": str(tmp_path)})
    assert r.status_code == 200 and str(tmp_path.resolve()) in r.json()["roots"]
    entries = auth_root_client.get("/api/v1/symbiote/feed").json()["entries"]
    assert entries[0]["what"] == "symbiote.pc.roots"


# ── Справжній вхід: ядро агента ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_the_kernel_reaches_pc_tools(auth_root_user):
    from agent.kernel.executor import execute
    from agent.schemas import PlanStep

    result, _ = await execute(
        user_id=auth_root_user.id, task_id=f"t-{uuid.uuid4()}",
        step=PlanStep(step_idx=0, action="pc.processes", args={"query": "fire"}),
        runtime=None, workspace_dir="/tmp",
    )
    assert result.ok, result.error
    assert [p["name"] for p in result.output["processes"]] == ["firefox"]
    whats = [(r["who"], r["what"], r["status"]) for r in _feed(auth_root_user.id)]
    assert ("mind", "pc.processes", "done") in whats


# ── ОС-шари: чисті частини ──────────────────────────────────────────────────


def test_linux_keys_parse_to_wtype_and_hyprland_windows_parse():
    from symbiote.pc.linux import parse_hypr_clients, parse_keys, wtype_argv

    assert wtype_argv(*parse_keys("ctrl+shift+t")) == ["wtype", "-M", "ctrl", "-M", "shift", "t", "-m", "shift", "-m", "ctrl"]
    assert wtype_argv(*parse_keys("alt+F4")) == ["wtype", "-M", "alt", "-k", "F4", "-m", "alt"]
    for bad in ("hyper+x", "ctrl+", "ctrl+рядок"):
        with pytest.raises(ValueError):
            parse_keys(bad)
    raw = json.dumps([
        {"address": "0x1", "title": "a", "class": "kitty", "pid": 5, "mapped": True, "focusHistoryID": 0,
         "workspace": {"name": "1"}},
        {"address": "0x2", "title": "b", "class": "code", "pid": 6, "mapped": True, "focusHistoryID": 1,
         "workspace": {"name": "2"}, "hidden": True},
        {"address": "0x3", "title": "ghost", "mapped": False},
    ])
    wins = parse_hypr_clients(raw)
    assert [(w.id, w.focused, w.visible, w.workspace) for w in wins] == [("0x1", True, True, "1"), ("0x2", False, False, "2")]


def test_linux_apps_come_only_from_desktop_entries(tmp_path):
    from symbiote.pc.linux import desktop_exec_argv, find_desktop_entry

    apps = tmp_path / "applications"
    apps.mkdir()
    (apps / "org.kde.kate.desktop").write_text(
        "[Desktop Entry]\nType=Application\nName=Kate\nExec=kate -b %U\n", encoding="utf-8")
    (apps / "hidden.desktop").write_text("[Desktop Entry]\nType=Application\nName=Secret\nNoDisplay=true\nExec=x\n")
    found = find_desktop_entry("kate", [apps])
    assert found is not None and found[0] == "org.kde.kate" and found[2] == "Kate"
    assert desktop_exec_argv(found[1]) == ["kate", "-b"]
    assert find_desktop_entry("secret", [apps]) is None
    assert find_desktop_entry("/usr/bin/rm", [apps]) is None


def test_windows_keys_map_to_virtual_key_codes():
    from symbiote.pc.windows import vk_sequence

    assert vk_sequence("ctrl+s") == [(0x11, True), (ord("S"), True), (ord("S"), False), (0x11, False)]
    assert vk_sequence("alt+F4")[1] == (0x73, True)


def test_guard_knows_secrets():
    from symbiote.pc import guard

    for name in ("id_ed25519", ".env", ".env.local", "vault.kdbx", "keystore.properties", "Login Data", "cert.p12"):
        assert guard.secret_path(Path("/home/u/work") / name), name
    for name in (".env.example", "notes.md", "main.py", "keys.md"):
        assert guard.secret_path(Path("/home/u/work") / name) is None, name
    assert guard.secret_path(Path("/home/u/.ssh/config"))
    assert guard.secret_clip("x", ["ExcludeClipboardContentFromMonitorProcessing"])
    assert guard.secret_clip("-----BEGIN RSA PRIVATE KEY-----", [])
    assert guard.secret_clip("купи молоко", ["text/plain"]) is None
    assert guard.visible_secret([Window(id="1", title="1Password", app="1password")])
    # на іншому робочому столі Hyprland — не на екрані
    assert guard.visible_secret([
        Window(id="1", title="code", app="code", focused=True, workspace="1"),
        Window(id="2", title="Bitwarden", app="bitwarden", workspace="9"),
    ]) is None
