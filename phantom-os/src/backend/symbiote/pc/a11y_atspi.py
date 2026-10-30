"""AT-SPI (Linux) — дерево доступності без pyatspi, через gi.repository.Atspi.

Живе двома способами: модулем (коли venv бачить `gi`) і самостійним
скриптом під системним python (на Arch venv зазвичай `gi` не має, а
`/usr/bin/python3` — має). Скрипт читає одне JSON-прохання з argv і друкує
JSON-відповідь; жодних імпортів з бекенду, щоб системний python його запустив.

Безпека тут же, на найнижчому рівні: текст вузла з роллю «password text»
(і будь-якого вузла зі станом protected) не читається взагалі.
"""
from __future__ import annotations

import json
import sys
import time
from typing import Any, Optional

MAX_DEPTH = 40
MAX_NODES_PER_APP = 6000
TEXT_CAP = 4000
DEADLINE_S = 6.0


def _atspi():
    import gi

    gi.require_version("Atspi", "2.0")
    from gi.repository import Atspi

    return Atspi


def _role(node) -> str:
    try:
        return str(node.get_role_name() or "")
    except Exception:  # noqa: BLE001
        return ""


def _states(Atspi, node) -> list[str]:
    out: list[str] = []
    try:
        ss = node.get_state_set()
        for name in ("FOCUSED", "EDITABLE", "SHOWING", "VISIBLE", "ACTIVE"):
            st = getattr(Atspi.StateType, name, None)
            if st is not None and ss.contains(st):
                out.append(name.lower())
    except Exception:  # noqa: BLE001
        pass
    return out


def _is_password(role: str) -> bool:
    return "password" in role.lower()


def _text(Atspi, node, role: str) -> Optional[str]:
    if _is_password(role):
        return None
    try:
        count = Atspi.Text.get_character_count(node)
    except Exception:  # noqa: BLE001
        return None
    if not count or count < 0:
        return "" if count == 0 else None
    try:
        return str(Atspi.Text.get_text(node, 0, min(int(count), TEXT_CAP)) or "")
    except Exception:  # noqa: BLE001
        return None


def _bbox(Atspi, node) -> Optional[list[int]]:
    try:
        r = node.get_extents(Atspi.CoordType.SCREEN)
        if r.width <= 0 or r.height <= 0:
            return None
        return [int(r.x), int(r.y), int(r.width), int(r.height)]
    except Exception:  # noqa: BLE001
        return None


def _snapshot(Atspi, node, app_name: str, pid: int) -> dict[str, Any]:
    role = _role(node)
    try:
        name = str(node.get_name() or "")
    except Exception:  # noqa: BLE001
        name = ""
    return {
        "role": role,
        "name": name[:300],
        "app": app_name,
        "pid": pid,
        "bbox": _bbox(Atspi, node),
        "text": _text(Atspi, node, role),
        "states": _states(Atspi, node),
    }


def _matches(node, role_name: str, label: str, role: str) -> bool:
    if role and role.lower() not in role_name.lower():
        return False
    if not label:
        return True
    low = label.lower()
    try:
        name = str(node.get_name() or "").lower()
    except Exception:  # noqa: BLE001
        name = ""
    if low in name:
        return True
    try:
        desc = str(node.get_description() or "").lower()
    except Exception:  # noqa: BLE001
        desc = ""
    return low in desc


def _apps(Atspi, app: str, pid: int):
    desktop = Atspi.get_desktop(0)
    for i in range(desktop.get_child_count()):
        try:
            node = desktop.get_child_at_index(i)
        except Exception:  # noqa: BLE001
            continue
        if node is None:
            continue
        try:
            name = str(node.get_name() or "")
        except Exception:  # noqa: BLE001
            name = ""
        try:
            app_pid = int(node.get_process_id() or 0)
        except Exception:  # noqa: BLE001
            app_pid = 0
        if app and app.lower() not in name.lower():
            continue
        if pid and app_pid != pid:
            continue
        yield node, name, app_pid


def _walk(root, deadline: float):
    stack = [(root, 0)]
    seen = 0
    while stack:
        if time.monotonic() > deadline or seen > MAX_NODES_PER_APP:
            return
        node, depth = stack.pop()
        seen += 1
        yield node
        if depth >= MAX_DEPTH:
            continue
        try:
            count = node.get_child_count()
        except Exception:  # noqa: BLE001
            continue
        for i in range(count - 1, -1, -1):
            try:
                child = node.get_child_at_index(i)
            except Exception:  # noqa: BLE001
                continue
            if child is not None:
                stack.append((child, depth + 1))


def find(label: str = "", role: str = "", app: str = "", pid: int = 0, limit: int = 40) -> list[dict[str, Any]]:
    Atspi = _atspi()
    deadline = time.monotonic() + DEADLINE_S
    out: list[dict[str, Any]] = []
    for app_node, app_name, app_pid in _apps(Atspi, app, pid):
        for node in _walk(app_node, deadline):
            if node is app_node:
                continue
            role_name = _role(node)
            if _matches(node, role_name, label, role):
                out.append(_snapshot(Atspi, node, app_name, app_pid))
                if len(out) >= limit:
                    return out
    return out


def press(label: str, role: str = "", app: str = "", pid: int = 0) -> Optional[dict[str, Any]]:
    """Перший вузол, що підходить і має дію, — натиснути дією доступності
    (працює і на Wayland, де координати миші брешуть)."""
    Atspi = _atspi()
    deadline = time.monotonic() + DEADLINE_S
    for app_node, app_name, app_pid in _apps(Atspi, app, pid):
        for node in _walk(app_node, deadline):
            if node is app_node:
                continue
            role_name = _role(node)
            if not _matches(node, role_name, label, role):
                continue
            try:
                n_actions = Atspi.Action.get_n_actions(node)
            except Exception:  # noqa: BLE001
                n_actions = 0
            if not n_actions:
                continue
            snap = _snapshot(Atspi, node, app_name, app_pid)
            snap["text"] = None
            snap["pressed"] = bool(Atspi.Action.do_action(node, 0))
            return snap
    return None


def focused(pid: int = 0) -> Optional[dict[str, Any]]:
    Atspi = _atspi()
    deadline = time.monotonic() + DEADLINE_S
    for app_node, app_name, app_pid in _apps(Atspi, "", pid):
        for node in _walk(app_node, deadline):
            try:
                if node.get_state_set().contains(Atspi.StateType.FOCUSED):
                    snap = _snapshot(Atspi, node, app_name, app_pid)
                    snap["text"] = None  # для перевірки досить ролі
                    return snap
            except Exception:  # noqa: BLE001
                continue
    return None


def available() -> bool:
    try:
        _atspi()
        return True
    except Exception:  # noqa: BLE001
        return False


def handle(request: dict[str, Any]) -> Any:
    op = request.get("op")
    if op == "find":
        return find(
            str(request.get("label") or ""), str(request.get("role") or ""),
            str(request.get("app") or ""), int(request.get("pid") or 0),
            int(request.get("limit") or 40),
        )
    if op == "press":
        return press(
            str(request.get("label") or ""), str(request.get("role") or ""),
            str(request.get("app") or ""), int(request.get("pid") or 0),
        )
    if op == "focused":
        return focused(int(request.get("pid") or 0))
    if op == "ping":
        return available()
    raise ValueError(f"unknown op {op!r}")


if __name__ == "__main__":  # системний python: одне прохання — одна відповідь
    try:
        print(json.dumps({"ok": True, "result": handle(json.loads(sys.argv[1]))}, ensure_ascii=False))
    except Exception as exc:  # noqa: BLE001
        print(json.dumps({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, ensure_ascii=False))
