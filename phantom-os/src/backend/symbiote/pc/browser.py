"""Браузер тіла ПК: власний профіль PHANTOM і Chrome DevTools Protocol.

* PHANTOM запускає свій Chromium/Chrome/Edge з окремим `--user-data-dir`
  у даних PHANTOM і говорить лише з ним — через порт із його ж
  `DevToolsActivePort`. Під'єднання до будь-якого іншого браузера (основного
  профілю власника) тут немає взагалі: це окреме рішення власника, не дієслово.
* Сторінка читається як підсумок дерева доступності (`Accessibility.getFullAXTree`):
  рядки `[ref] роль «назва» = значення`. Значення полів пароля не читаються.
* Клацання й заповнення — за `ref` (backendDOMNodeId з підсумку). Перед дією
  сторож дивиться на сам елемент: покупка/оплата, вхід/реєстрація, поле пароля,
  картки, коди — відмова: «це робить лише власник».
"""
from __future__ import annotations

import asyncio
import itertools
import json
import os
import shutil
import sys
from pathlib import Path
from typing import Any, Optional, Protocol
from urllib.parse import urlparse

from symbiote.pc.backend import PcRefused, PcUnsupported
from symbiote.pc.guard import LOGIN_WORDS, PURCHASE_WORDS, SENSITIVE_FIELD

LAUNCH_WAIT_S = 15.0
LOAD_WAIT_S = 12.0
SUMMARY_LINES = 400

_INTERESTING = {
    "link", "button", "textbox", "searchbox", "combobox", "checkbox", "radio", "switch",
    "heading", "img", "menuitem", "tab", "option", "listbox", "slider", "spinbutton",
    "StaticText", "paragraph", "cell", "columnheader", "rowheader", "alert", "dialog",
}
_ACTIONABLE = {"link", "button", "textbox", "searchbox", "combobox", "checkbox", "radio",
               "switch", "menuitem", "tab", "option", "slider", "spinbutton"}


class Transport(Protocol):
    async def send(self, method: str, params: Optional[dict[str, Any]] = None) -> dict[str, Any]: ...


class WsTransport:
    """Одне з'єднання з вкладкою; id запитів по порядку, події пропускаються."""

    def __init__(self, ws_url: str) -> None:
        self._url = ws_url
        self._ws = None
        self._ids = itertools.count(1)

    async def __aenter__(self) -> "WsTransport":
        import websockets

        self._ws = await websockets.connect(self._url, max_size=64 * 1024 * 1024, open_timeout=8)
        return self

    async def __aexit__(self, *exc) -> None:
        if self._ws is not None:
            await self._ws.close()

    async def send(self, method: str, params: Optional[dict[str, Any]] = None) -> dict[str, Any]:
        assert self._ws is not None
        msg_id = next(self._ids)
        await self._ws.send(json.dumps({"id": msg_id, "method": method, "params": params or {}}))
        while True:
            raw = await asyncio.wait_for(self._ws.recv(), timeout=20)
            msg = json.loads(raw)
            if msg.get("id") != msg_id:
                continue
            if "error" in msg:
                raise PcUnsupported(f"браузер: {msg['error'].get('message', 'помилка')}")
            return msg.get("result") or {}


def profile_dir() -> Path:
    from paths import data_root

    return data_root() / "symbiote" / "browser-profile"


def find_browser() -> Optional[str]:
    env = os.environ.get("PHANTOM_BROWSER")
    if env and Path(env).exists():
        return env
    names = ("chromium", "chromium-browser", "google-chrome-stable", "google-chrome",
             "microsoft-edge-stable", "microsoft-edge", "brave-browser", "brave")
    for name in names:
        found = shutil.which(name)
        if found:
            return found
    candidates: list[Path] = []
    if sys.platform.startswith("win"):
        for env_name in ("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA"):
            base = os.environ.get(env_name)
            if base:
                candidates += [Path(base) / "Google/Chrome/Application/chrome.exe",
                               Path(base) / "Microsoft/Edge/Application/msedge.exe",
                               Path(base) / "Chromium/Application/chrome.exe"]
    elif sys.platform == "darwin":
        candidates += [Path("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                       Path("/Applications/Chromium.app/Contents/MacOS/Chromium")]
    return next((str(c) for c in candidates if c.exists()), None)


def launch_argv(binary: str, profile: Path, *, headless: bool = False) -> list[str]:
    argv = [binary, f"--user-data-dir={profile}", "--remote-debugging-port=0",
            "--remote-debugging-address=127.0.0.1", "--no-first-run", "--no-default-browser-check",
            "--disable-sync", "--password-store=basic", "--disable-features=Translate,AutofillServerCommunication",
            "--disable-save-password-bubble"]
    if headless:
        argv.append("--headless=new")
    return argv + ["about:blank"]


class PhantomBrowser:
    """Власний браузер PHANTOM. `transport_factory` — для тестів."""

    def __init__(self) -> None:
        self._proc: Optional[asyncio.subprocess.Process] = None
        self.headless = os.environ.get("PHANTOM_BROWSER_HEADLESS") == "1"
        self.transport_factory = None  # callable() -> async context manager Transport

    def _port_file(self) -> Path:
        return profile_dir() / "DevToolsActivePort"

    def _read_port(self) -> Optional[int]:
        try:
            first = self._port_file().read_text().splitlines()[0].strip()
            return int(first)
        except (OSError, ValueError, IndexError):
            return None

    async def _alive_port(self) -> Optional[int]:
        port = self._read_port()
        if port is None:
            return None
        try:
            import httpx

            async with httpx.AsyncClient(timeout=2.0) as client:
                r = await client.get(f"http://127.0.0.1:{port}/json/version")
                return port if r.status_code == 200 else None
        except Exception:  # noqa: BLE001
            return None

    async def ensure(self) -> int:
        port = await self._alive_port()
        if port:
            return port
        binary = find_browser()
        if binary is None:
            raise PcUnsupported("немає Chromium, Chrome чи Edge для браузера PHANTOM")
        profile = profile_dir()
        profile.mkdir(parents=True, exist_ok=True)
        self._port_file().unlink(missing_ok=True)
        self._proc = await asyncio.create_subprocess_exec(
            *launch_argv(binary, profile, headless=self.headless),
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
            stdin=asyncio.subprocess.DEVNULL, start_new_session=not sys.platform.startswith("win"),
        )
        for _ in range(int(LAUNCH_WAIT_S / 0.2)):
            await asyncio.sleep(0.2)
            port = await self._alive_port()
            if port:
                return port
        raise PcUnsupported("браузер PHANTOM не відкрив порт DevTools")

    async def _page_ws(self, port: int) -> str:
        import httpx

        async with httpx.AsyncClient(timeout=4.0) as client:
            targets = (await client.get(f"http://127.0.0.1:{port}/json/list")).json()
            pages = [t for t in targets if t.get("type") == "page" and t.get("webSocketDebuggerUrl")]
            if not pages:
                await client.put(f"http://127.0.0.1:{port}/json/new?about:blank")
                targets = (await client.get(f"http://127.0.0.1:{port}/json/list")).json()
                pages = [t for t in targets if t.get("type") == "page" and t.get("webSocketDebuggerUrl")]
        if not pages:
            raise PcUnsupported("у браузері PHANTOM немає вкладки")
        return str(pages[0]["webSocketDebuggerUrl"])

    def _connect(self, ws_url: str):
        return WsTransport(ws_url)

    async def session(self):
        if self.transport_factory is not None:
            return self.transport_factory()
        port = await self.ensure()
        return self._connect(await self._page_ws(port))

    # ── дієслова ────────────────────────────────────────────────────────────

    async def open(self, url: str) -> dict[str, Any]:
        parsed = urlparse(url)
        if parsed.scheme.lower() not in ("http", "https") or not parsed.netloc:
            raise PcRefused("браузер PHANTOM відкриває лише http(s)-адреси")
        async with await self.session() as t:
            await t.send("Page.enable")
            await t.send("Page.navigate", {"url": url})
            await _wait_loaded(t)
            return await _page_head(t)

    async def read(self) -> dict[str, Any]:
        async with await self.session() as t:
            head = await _page_head(t)
            return {**head, **await summarize(t)}

    async def click(self, ref: str) -> dict[str, Any]:
        async with await self.session() as t:
            obj = await _resolve(t, ref)
            info = await _inspect(t, obj)
            why = click_refusal(info)
            if why:
                raise PcRefused(why)
            await t.send("Runtime.callFunctionOn", {
                "objectId": obj, "returnByValue": True,
                "functionDeclaration": "function(){this.scrollIntoView({block:'center'});this.click();return true}",
            })
            await asyncio.sleep(0.3)
            return {"clicked": info.get("text") or info.get("tag"), **await _page_head(t)}

    async def fill(self, ref: str, text: str) -> dict[str, Any]:
        async with await self.session() as t:
            obj = await _resolve(t, ref)
            info = await _inspect(t, obj)
            why = fill_refusal(info)
            if why:
                raise PcRefused(why)
            await t.send("Runtime.callFunctionOn", {
                "objectId": obj, "returnByValue": True, "arguments": [{"value": text}],
                "functionDeclaration": (
                    "function(v){this.focus();"
                    "const p=Object.getPrototypeOf(this);const d=Object.getOwnPropertyDescriptor(p,'value');"
                    "if(d&&d.set){d.set.call(this,v)}else if(this.isContentEditable){this.textContent=v}else{this.value=v}"
                    "this.dispatchEvent(new Event('input',{bubbles:true}));"
                    "this.dispatchEvent(new Event('change',{bubbles:true}));return true}"
                ),
            })
            return {"filled": info.get("label") or info.get("name") or info.get("tag"), "chars": len(text)}

    async def close(self) -> None:
        if self._proc is not None and self._proc.returncode is None:
            self._proc.terminate()


async def _wait_loaded(t: Transport) -> None:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + LOAD_WAIT_S
    while loop.time() < deadline:
        r = await t.send("Runtime.evaluate", {"expression": "document.readyState", "returnByValue": True})
        if (r.get("result") or {}).get("value") in ("interactive", "complete"):
            return
        await asyncio.sleep(0.2)


async def _page_head(t: Transport) -> dict[str, Any]:
    r = await t.send("Runtime.evaluate", {
        "expression": "JSON.stringify({title: document.title, url: location.href})", "returnByValue": True,
    })
    try:
        return json.loads((r.get("result") or {}).get("value") or "{}")
    except ValueError:
        return {}


async def _password_ids(t: Transport) -> set[int]:
    doc = await t.send("DOM.getDocument", {"depth": -1, "pierce": True})
    ids: set[int] = set()

    def walk(node: dict[str, Any]) -> None:
        attrs = node.get("attributes") or []
        pairs = dict(zip(attrs[::2], attrs[1::2]))
        if node.get("nodeName", "").upper() == "INPUT" and (
            pairs.get("type", "").lower() == "password"
            or SENSITIVE_FIELD.search(pairs.get("autocomplete", "") + " " + pairs.get("name", ""))
        ):
            ids.add(int(node.get("backendNodeId") or 0))
        for key in ("children", "shadowRoots"):
            for child in node.get(key) or []:
                walk(child)
        if node.get("contentDocument"):
            walk(node["contentDocument"])

    walk(doc.get("root") or {})
    return ids


def _ax_value(prop: Optional[dict[str, Any]]) -> str:
    if not prop:
        return ""
    v = prop.get("value")
    return "" if v is None else str(v)


async def summarize(t: Transport) -> dict[str, Any]:
    secret_ids = await _password_ids(t)
    await t.send("Accessibility.enable")
    tree = await t.send("Accessibility.getFullAXTree")
    lines: list[str] = []
    refs: dict[str, dict[str, Any]] = {}
    login = bool(secret_ids)
    for node in tree.get("nodes") or []:
        if node.get("ignored"):
            continue
        role = _ax_value(node.get("role"))
        if role not in _INTERESTING:
            continue
        name = _ax_value(node.get("name")).strip()
        backend = int(node.get("backendDOMNodeId") or 0)
        secret = backend in secret_ids or any(
            p.get("name") == "protected" and (p.get("value") or {}).get("value") for p in node.get("properties") or []
        )
        value = "" if secret else _ax_value(node.get("value")).strip()
        if role == "StaticText" and (not name or len(lines) > SUMMARY_LINES):
            continue
        if role in _ACTIONABLE and backend:
            ref = str(backend)
            refs[ref] = {"role": role, "name": name[:120], "secret": secret}
            line = f"[{ref}] {role} «{name[:160]}»"
        else:
            line = f"{role} «{name[:300]}»"
        if secret:
            line += " = (поле пароля — не читається)"
        elif value:
            line += f" = {value[:200]}"
        lines.append(line)
        if len(lines) >= SUMMARY_LINES:
            break
    return {"lines": lines, "refs": refs, "login_form": login,
            "note": "на сторінці є поле пароля — входи робить лише власник" if login else ""}


async def _resolve(t: Transport, ref: str) -> str:
    if not ref.isdigit():
        raise PcRefused("ref — число з підсумку сторінки")
    r = await t.send("DOM.resolveNode", {"backendNodeId": int(ref)})
    obj = (r.get("object") or {}).get("objectId")
    if not obj:
        raise PcRefused("такого елемента на сторінці вже немає — прочитай сторінку знову")
    return str(obj)


_INSPECT = """function(){
  const el=this, form=el.form||(el.closest&&el.closest('form'));
  const txt=((el.innerText||el.value||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.title||'')).trim().slice(0,200);
  const lab=(el.labels&&el.labels[0]&&el.labels[0].innerText)||el.getAttribute('aria-label')||el.placeholder||'';
  const formText=form?((form.getAttribute('action')||'')+' '+(form.id||'')+' '+(form.className||'')+' '+
    Array.from(form.querySelectorAll('button,input[type=submit]')).map(b=>b.innerText||b.value||'').join(' ')).slice(0,400):'';
  return JSON.stringify({tag:(el.tagName||'').toLowerCase(),type:(el.type||'').toLowerCase(),
    name:el.name||'',id:el.id||'',autocomplete:el.autocomplete||'',text:txt,label:lab,
    href:el.href||'',formHasPassword:!!(form&&form.querySelector('input[type=password]')),
    formText:formText,editable:!!el.isContentEditable,isSubmit:(el.type||'').toLowerCase()==='submit'});
}"""


async def _inspect(t: Transport, obj: str) -> dict[str, Any]:
    r = await t.send("Runtime.callFunctionOn", {"objectId": obj, "functionDeclaration": _INSPECT, "returnByValue": True})
    try:
        return json.loads((r.get("result") or {}).get("value") or "{}")
    except ValueError:
        return {}


def click_refusal(info: dict[str, Any]) -> Optional[str]:
    words = f"{info.get('text', '')} {info.get('label', '')} {info.get('href', '')}"
    if PURCHASE_WORDS.search(words):
        return "покупки й оплату робить лише власник"
    if info.get("formHasPassword") and (info.get("isSubmit") or info.get("tag") == "button"):
        return "вхід у акаунти робить лише власник"
    if LOGIN_WORDS.search(info.get("text", "")) and (info.get("isSubmit") or info.get("tag") == "button"):
        return "вхід і реєстрацію робить лише власник"
    if info.get("isSubmit") and PURCHASE_WORDS.search(info.get("formText", "")):
        return "форма схожа на оплату — це робить лише власник"
    if info.get("type") == "password":
        return "поле пароля — лише власник"
    return None


def fill_refusal(info: dict[str, Any]) -> Optional[str]:
    if info.get("type") == "password":
        return "поле пароля заповнює лише власник"
    fields = " ".join(str(info.get(k, "")) for k in ("name", "id", "autocomplete", "label"))
    if SENSITIVE_FIELD.search(fields):
        return "паролі, коди й дані карток вводить лише власник"
    if info.get("formHasPassword"):
        return "це форма входу — входи робить лише власник"
    text_inputs = ("", "text", "search", "email", "url", "tel", "number")
    if info.get("tag") == "textarea" or info.get("editable"):
        return None
    if info.get("tag") == "input" and info.get("type", "") in text_inputs:
        return None
    return "це не поле для тексту"


browser = PhantomBrowser()

__all__ = ["PhantomBrowser", "browser", "summarize", "click_refusal", "fill_refusal",
           "find_browser", "launch_argv", "profile_dir", "WsTransport"]
