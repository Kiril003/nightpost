"""Руки й очі тіла ПК: дієслова `pc.*` контракту (`pc_body.verbs`) → справа.

Кожна рука повертає `(ok, detail, payload)`, як `desktop_hands`: `detail` —
коротко й людською мовою (іде в стрічку «Що я роблю» і на екран телефона),
`payload` — дані для того, хто просив (розум чи телефон), у стрічку не
потрапляє. Сторож (`guard`) стоїть усередині кожної руки, тож вона однаково
безпечна, хто б її не кликав і після чийого б «так».
"""
from __future__ import annotations

import asyncio
import logging
import shutil
from typing import Any, Awaitable, Callable

from symbiote.pc import files, guard, shell
from symbiote.pc.backend import PcRefused, PcUnsupported, get_backend
from symbiote.pc.browser import browser

logger = logging.getLogger(__name__)

Result = tuple[bool, str, dict[str, Any]]

DEFAULT_SEE_PROMPT = (
    "Опиши коротко українською, що зараз на екрані: які програми й вікна, що в них "
    "відкрито, який текст головний. Не переписуй паролі, коди й особисті дані."
)


async def _focused():
    try:
        return await get_backend().ui_focused()
    except Exception:  # noqa: BLE001 — не знати фокус ≠ знати, що там пароль
        return None


async def _screen_secret() -> None:
    backend = get_backend()
    try:
        wins = await backend.windows()
    except PcUnsupported:
        wins = []
    why = guard.visible_secret(wins)
    if why:
        raise PcRefused(f"екран не знімаю: {why}")
    why = guard.password_element(await _focused())
    if why:
        raise PcRefused(f"екран не знімаю: {why}")


async def _active_secret(what: str) -> None:
    """Активне вікно — менеджер паролів чи в фокусі поле пароля → відмова.
    Коли ОС не каже, яке вікно активне, — сторож мовчить (драбина й «так»
    лишаються); коли каже — слово сторожа остаточне."""
    backend = get_backend()
    try:
        win = await backend.active_window()
    except Exception:  # noqa: BLE001
        win = None
    if win is not None:
        why = guard.secret_window(win)
        if why:
            raise PcRefused(f"{what}: {why}")
    why = guard.password_element(await _focused())
    if why:
        raise PcRefused(f"{what}: {why}")


async def _ocr(png: bytes) -> str:
    exe = shutil.which("tesseract")
    if exe is None:
        raise PcUnsupported("розпізнавання тексту: немає tesseract")
    proc = await asyncio.create_subprocess_exec(
        exe, "stdin", "stdout", "-l", "ukr+eng",
        stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    out, err = await asyncio.wait_for(proc.communicate(png), timeout=30)
    if proc.returncode != 0:
        # Без українського пакета — англійською, ніж ніяк.
        proc = await asyncio.create_subprocess_exec(
            exe, "stdin", "stdout", stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        out, err = await asyncio.wait_for(proc.communicate(png), timeout=30)
        if proc.returncode != 0:
            raise PcUnsupported("tesseract не розпізнав знімок")
    return out.decode("utf-8", errors="replace").strip()


# ── Бачити ──────────────────────────────────────────────────────────────────


async def see(args: dict[str, Any]) -> Result:
    await _screen_secret()
    png = await get_backend().screenshot()
    mode = (args.get("mode") or "describe").lower()
    if mode not in ("describe", "ocr"):
        raise PcRefused("режим погляду: describe або ocr")
    if mode == "describe":
        from ai import model_router

        try:
            seen = await model_router.see(png, str(args.get("prompt") or DEFAULT_SEE_PROMPT))
            return True, f"Подивився ({seen.provider})", {"text": seen.text, "provider": seen.provider, "mode": "describe"}
        except model_router.NoRouteError:
            mode = "ocr"  # моделі з зором немає — хоч прочитаємо текст локально
    text = await _ocr(png)
    return True, "Прочитав текст з екрана", {"text": text, "provider": "tesseract", "mode": "ocr"}


async def windows(_args: dict[str, Any]) -> Result:
    wins = await get_backend().windows()
    rows = [guard.redact_window(w) for w in wins]
    return True, f"Вікон: {len(rows)}", {"windows": rows}


async def ui_find(args: dict[str, Any]) -> Result:
    backend = get_backend()
    found = await backend.ui_find(label=str(args.get("label") or ""), role=str(args.get("role") or ""),
                                  app=str(args.get("app") or ""))
    rows = []
    for el in found:
        if guard.secret_app_name(el.app):
            continue
        rows.append(el.to_dict())
    return True, f"Знайдено елементів: {len(rows)}", {"elements": rows}


async def clipboard_read(_args: dict[str, Any]) -> Result:
    await _active_secret("буфер не читаю")
    clip = await get_backend().clipboard_read()
    why = guard.secret_clip(clip.text, clip.types)
    if why:
        raise PcRefused(f"буфер не читаю: {why}")
    return True, f"Прочитав буфер ({len(clip.text)} символів)", {"text": clip.text}


async def processes(args: dict[str, Any]) -> Result:
    procs = await get_backend().processes()
    q = str(args.get("query") or "").lower()
    if q:
        procs = [p for p in procs if q in p["name"].lower()]
    return True, f"Процесів: {len(procs)}", {"processes": procs[:80], "total": len(procs)}


async def files_list(args: dict[str, Any]) -> Result:
    out = await asyncio.to_thread(files.list_dir, str(args.get("path") or ""))
    return True, f"У теці: {len(out['entries'])}", out


async def files_search(args: dict[str, Any]) -> Result:
    content = str(args.get("content") or "").lower() in ("1", "true", "так", "yes")
    out = await asyncio.to_thread(files.search, str(args["query"]), str(args.get("path") or ""), content)
    return True, f"Знайдено: {len(out['results'])}", out


async def files_read(args: dict[str, Any]) -> Result:
    out = await asyncio.to_thread(files.read, str(args["path"]))
    return True, f"Прочитав {out['size']} байт", out


async def browser_read(_args: dict[str, Any]) -> Result:
    out = await browser.read()
    return True, f"Сторінка: {str(out.get('title') or '')[:80]}", out


# ── Діяти ───────────────────────────────────────────────────────────────────


async def app_open(args: dict[str, Any]) -> Result:
    app = str(args["app"]).strip()
    why = guard.secret_app_name(app)
    if why:
        raise PcRefused(why)
    name = await get_backend().launch_app(app)
    return True, f"Відкрив «{name}»", {"app": name}


async def hotkey(args: dict[str, Any]) -> Result:
    from symbiote.pc.linux import parse_keys

    keys = str(args["keys"])
    try:
        parse_keys(keys)
    except ValueError as exc:
        raise PcRefused(str(exc)) from exc
    await _active_secret("клавіші не натискаю")
    await get_backend().hotkey([k for k in keys.split("+") if k])
    return True, f"Натиснув {keys}", {}


async def click(args: dict[str, Any]) -> Result:
    backend = get_backend()
    label = str(args.get("label") or "")
    button = str(args.get("button") or "left")
    if button not in ("left", "right", "middle"):
        raise PcRefused("кнопка миші: left, right або middle")
    if label:
        app = str(args.get("app") or "")
        if guard.secret_app_name(app):
            raise PcRefused(guard.secret_app_name(app) or "")
        found = await backend.ui_find(label=label, role=str(args.get("role") or ""), app=app, limit=5)
        found = [e for e in found if not guard.secret_app_name(e.app)]
        if not found:
            return False, f"Не знайшов «{label}» на екрані", {}
        target = found[0]
        if target.is_password:
            raise PcRefused("у поле пароля не клацаю — це робить власник")
        try:
            pressed = await backend.ui_press(label=label, role=str(args.get("role") or ""), app=target.app)
        except PcUnsupported:
            pressed = None
        if pressed is not None:
            return True, f"Натиснув «{pressed.name or label}»", {"element": pressed.to_dict(), "how": "accessibility"}
        if target.bbox:
            x, y, w, h = target.bbox
            await backend.click(x + max(1, w // 2), y + max(1, h // 2), button)
            return True, f"Клацнув «{target.name or label}»", {"element": target.to_dict(), "how": "pointer"}
        return False, f"«{label}» не натискається і не має місця на екрані", {}
    try:
        x, y = int(str(args.get("x"))), int(str(args.get("y")))
    except ValueError as exc:
        raise PcRefused("x і y — цілі числа") from exc
    await _active_secret("не клацаю")
    await backend.click(x, y, button)
    return True, f"Клацнув у ({x}, {y})", {"how": "pointer"}


async def files_write(args: dict[str, Any]) -> Result:
    out = await asyncio.to_thread(files.write, str(args["path"]), str(args.get("text") or ""),
                                  str(args.get("mode") or "create"))
    kept = " (стара версія збережена)" if out.get("backup") else ""
    return True, f"Записав {out['bytes']} байт{kept}", out


async def files_move(args: dict[str, Any]) -> Result:
    out = await asyncio.to_thread(files.move, str(args["src"]), str(args["dst"]))
    return True, "Перемістив", out


async def files_delete(args: dict[str, Any]) -> Result:
    out = await asyncio.to_thread(files.delete, str(args["path"]))
    return True, "Видалив (копія у сховищі версій)", out


async def shell_run(args: dict[str, Any]) -> Result:
    out = await shell.run(str(args["cmd"]), str(args.get("cwd") or ""))
    ok = out["exit"] == 0 and not out["timed_out"]
    head = f"$ {shell.summary(str(args['cmd']))} → {out['exit']}"
    if out["timed_out"]:
        head += " (перевищено час)"
    return ok, head, out


async def browser_open(args: dict[str, Any]) -> Result:
    out = await browser.open(str(args["url"]))
    return True, f"Відкрив сторінку «{str(out.get('title') or '')[:80]}»", out


async def browser_click(args: dict[str, Any]) -> Result:
    out = await browser.click(str(args["ref"]))
    return True, f"Клацнув «{str(out.get('clicked') or '')[:80]}»", out


async def browser_fill(args: dict[str, Any]) -> Result:
    out = await browser.fill(str(args["ref"]), str(args["text"]))
    return True, f"Заповнив «{str(out.get('filled') or '')[:80]}»", out


HANDS: dict[str, Callable[[dict[str, Any]], Awaitable[Result]]] = {
    "pc.see": see,
    "pc.windows": windows,
    "pc.ui.find": ui_find,
    "pc.clipboard.read": clipboard_read,
    "pc.processes": processes,
    "pc.files.list": files_list,
    "pc.files.search": files_search,
    "pc.files.read": files_read,
    "pc.browser.read": browser_read,
    "pc.app.open": app_open,
    "pc.hotkey": hotkey,
    "pc.click": click,
    "pc.files.write": files_write,
    "pc.files.move": files_move,
    "pc.files.delete": files_delete,
    "pc.shell": shell_run,
    "pc.browser.open": browser_open,
    "pc.browser.click": browser_click,
    "pc.browser.fill": browser_fill,
}


def knows(verb: str) -> bool:
    return verb in HANDS


async def execute(verb: str, args: dict[str, Any]) -> Result:
    hand = HANDS.get(verb)
    if hand is None:
        return False, "Комп'ютер цього ще не вміє", {}
    try:
        return await hand(dict(args or {}))
    except PcRefused as exc:
        return False, f"Відмовлено: {exc}", {"refused": True}
    except PcUnsupported as exc:
        return False, str(exc), {"unsupported": True}
    except (OSError, ValueError) as exc:
        logger.info("symbiote.pc: %s → %r", verb, exc)
        return False, f"Не вийшло: {exc}", {}
    except Exception as exc:  # noqa: BLE001
        logger.warning("symbiote.pc: рука %s зламалась: %r", verb, exc)
        return False, "Не вийшло", {}


__all__ = ["HANDS", "execute", "knows"]
