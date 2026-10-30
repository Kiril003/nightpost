"""Спільний контракт тіл симбіота.

Дієслова, сфери, рівні драбини і шкала ризику досі жили двічі: у
`commands.py`/`ladder.py` на ПК і в `BodyLadder.RULES` на телефоні, набрані
руками. Розійтися їм не заважало ніщо. Тепер джерело одне — `contract.json`
поруч; ПК читає його тут, телефон звіряється з ним тестом.

Шкала ризику статуту — ЧИТАННЯ · ЛОКАЛЬНА ДІЯ · ДІЯ НАЗОВНІ · НЕЗВОРОТНА —
одна на обидва тіла; старі шкали (`agent.schemas.RiskLevel`, телефонний
`ToolRisk`) відображаються на неї через `RISK_FROM_*`.

Аргументи дротом — лише рядки (телефон приймає `Map<String,String>`), тож
схема проста: які ключі є, які обов'язкові, яка найбільша довжина.
Невідомий ключ — помилка: лист із зайвим полем, яке тіло мовчки викине,
гірший за чесну відмову.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

CONTRACT_PATH = Path(__file__).with_name("contract.json")

_RAW: dict[str, Any] = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))

VERSION: int = int(_RAW["version"])
RISKS: dict[str, str] = dict(_RAW["risks"])
SPHERES: dict[str, str] = dict(_RAW["spheres"])
LEVELS: dict[str, str] = dict(_RAW["levels"])
VERBS: dict[str, dict[str, Any]] = {k: dict(v) for k, v in _RAW["verbs"].items()}

# Тіло ПК (`symbiote/pc`): свої сфери й дієслова. Окремо від `spheres`/`verbs`,
# бо телефонна BodyLadder.RULES звіряє саме ті два списки; ПК знає обидва.
_PC: dict[str, Any] = dict(_RAW.get("pc_body") or {})
PC_SPHERES: dict[str, str] = dict(_PC.get("spheres") or {})
PC_VERBS: dict[str, dict[str, Any]] = {k: dict(v) for k, v in (_PC.get("verbs") or {}).items()}

#: Усі сфери й дієслова, які ПК приймає (і від розуму, і від телефона).
ALL_SPHERES: dict[str, str] = {**SPHERES, **PC_SPHERES}
ALL_VERBS: dict[str, dict[str, Any]] = {**VERBS, **PC_VERBS}

#: Сходинка сфери після сполучення / для розуму на ПК, поки людина не змінила.
#: Сфери телефонного контракту — «бачити» (як було), сфери тіла ПК — з контракту.
DEFAULT_LEVELS: dict[str, str] = {
    **{sphere: "see" for sphere in SPHERES},
    **{sphere: str((_PC.get("defaults") or {}).get(sphere, "off")) for sphere in PC_SPHERES},
}


def ladder_kind(verb: str) -> str:
    """Вид дієслова для драбини. Читання з `consent: ask` (буфер обміну)
    поводиться як дія: на «бачити» — відмова, на «так» — питає."""
    spec = ALL_VERBS.get(verb) or {}
    if spec.get("consent") == "ask" and spec.get("kind") == "read":
        return "act"
    return str(spec.get("kind", "act"))

RISK_ORDER: tuple[str, ...] = tuple(RISKS)

#: телефонний `ToolRisk` → шкала статуту.
RISK_FROM_PHONE: dict[str, str] = {
    "READ": "read",
    "DEVICE": "local",
    "OUTWARD": "outward",
}

#: `agent.schemas.RiskLevel` (за іменем) → шкала статуту.
RISK_FROM_AGENT: dict[str, str] = {
    "SAFE": "read",
    "LOW": "local",
    "MEDIUM": "outward",
    "HIGH": "irreversible",
}

_KINDS = {"read", "act", "stop"}
_BODIES = {"phone", "desktop"}


def contract_problems() -> list[str]:
    """Що в самому контракті не сходиться. Порожньо — контракт цілий."""
    out: list[str] = []
    for sphere in PC_SPHERES:
        if sphere in SPHERES:
            out.append(f"сфера {sphere!r} і в телефонному, і в ПК-контракті")
        if DEFAULT_LEVELS.get(sphere) not in LEVELS:
            out.append(f"сфера {sphere!r}: типова сходинка {DEFAULT_LEVELS.get(sphere)!r}")
    for verb in PC_VERBS:
        if verb in VERBS:
            out.append(f"{verb}: і в телефонному, і в ПК-контракті")
        elif PC_VERBS[verb].get("body") != "desktop":
            out.append(f"{verb}: дієслово тіла ПК не для ПК")
    for verb, spec in ALL_VERBS.items():
        if spec.get("body") not in _BODIES:
            out.append(f"{verb}: тіло {spec.get('body')!r}")
        if spec.get("kind") not in _KINDS:
            out.append(f"{verb}: вид {spec.get('kind')!r}")
        if spec.get("risk") not in RISKS:
            out.append(f"{verb}: ризик {spec.get('risk')!r}")
        sphere = spec.get("sphere", "")
        if spec.get("kind") != "stop" and sphere not in ALL_SPHERES:
            out.append(f"{verb}: сфера {sphere!r}")
        if spec.get("kind") == "read" and spec.get("risk") != "read":
            out.append(f"{verb}: читання з ризиком {spec.get('risk')!r}")
        if not verb.startswith(("phone.", "pc.")):
            out.append(f"{verb}: дієслово без тіла в імені")
        for group in spec.get("one_of", []):
            for key in group:
                if key not in spec.get("args", {}):
                    out.append(f"{verb}: one_of згадує невідомий {key!r}")
    return out


def check_args(verb: str, args: dict[str, Any] | None) -> str:
    """Порожній рядок — аргументи годяться; інакше — людською мовою чому ні."""
    spec = ALL_VERBS.get(verb)
    if spec is None:
        return f"невідома команда: {verb}"
    schema: dict[str, dict[str, Any]] = spec.get("args", {})
    args = args or {}
    for key, value in args.items():
        if key not in schema:
            return f"«{verb}» не знає аргументу «{key}»"
        if not isinstance(value, str):
            return f"«{key}» має бути рядком"
        limit = schema[key].get("max")
        if limit is not None and len(value) > int(limit):
            return f"«{key}» задовгий: {len(value)} > {limit}"
    for key, rule in schema.items():
        if rule.get("required") and not str(args.get(key) or "").strip():
            return f"«{verb}» потребує «{key}»"
    for group in spec.get("one_of", []):
        if not any(str(args.get(k) or "").strip() for k in group):
            return f"«{verb}» потребує одне з: {', '.join(group)}"
    return ""


def is_irreversible(verb: str) -> bool:
    spec = ALL_VERBS.get(verb)
    return bool(spec) and spec["risk"] == "irreversible"


__all__ = [
    "CONTRACT_PATH",
    "VERSION",
    "RISKS",
    "RISK_ORDER",
    "RISK_FROM_PHONE",
    "RISK_FROM_AGENT",
    "SPHERES",
    "LEVELS",
    "VERBS",
    "PC_SPHERES",
    "PC_VERBS",
    "ALL_SPHERES",
    "ALL_VERBS",
    "DEFAULT_LEVELS",
    "ladder_kind",
    "contract_problems",
    "check_args",
    "is_irreversible",
]
