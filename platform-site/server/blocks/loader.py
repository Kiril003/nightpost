"""Registry and pack loader with draft 2020-12 schema check and folder pin."""
from __future__ import annotations

import copy
import hashlib
import json
import re
from pathlib import Path
from typing import Any


def _check_node(val: Any, node: dict, root: dict, path: str) -> None:
    if "$ref" in node:
        ref = node["$ref"]
        if not ref.startswith("#/$defs/"):
            raise ValueError(f"{path}: unsupported $ref {ref}")
        target = root["$defs"][ref.split("/")[-1]]
        _check_node(val, target, root, path)
        return

    if "oneOf" in node:
        errors = []
        matched = 0
        for sub in node["oneOf"]:
            try:
                _check_node(val, sub, root, path)
                matched += 1
            except ValueError as e:
                errors.append(str(e))
        if matched != 1:
            raise ValueError(f"{path}: oneOf matched {matched} schemas ({'; '.join(errors)})")
        return

    if "const" in node and val != node["const"]:
        raise ValueError(f"{path}: expected const {node['const']!r}, got {val!r}")

    t = node.get("type")
    if t == "object":
        if not isinstance(val, dict):
            raise ValueError(f"{path}: expected object")
        req = set(node.get("required", ()))
        missing = req - set(val.keys())
        if missing:
            raise ValueError(f"{path}: missing required keys {sorted(missing)}")
        if "minProperties" in node and len(val) < node["minProperties"]:
            raise ValueError(f"{path}: too few properties")
        if "maxProperties" in node and len(val) > node["maxProperties"]:
            raise ValueError(f"{path}: too many properties")
        props = node.get("properties", {})
        addl = node.get("additionalProperties", True)
        for k, v in val.items():
            sub_path = f"{path}.{k}"
            if k in props:
                _check_node(v, props[k], root, sub_path)
            elif addl is False:
                raise ValueError(f"{path}: unexpected property {k!r}")
            elif isinstance(addl, dict):
                _check_node(v, addl, root, sub_path)
    elif t == "array":
        if not isinstance(val, list):
            raise ValueError(f"{path}: expected array")
        if "minItems" in node and len(val) < node["minItems"]:
            raise ValueError(f"{path}: too few items")
        if "maxItems" in node and len(val) > node["maxItems"]:
            raise ValueError(f"{path}: too many items")
        if "items" in node:
            for idx, item in enumerate(val):
                _check_node(item, node["items"], root, f"{path}[{idx}]")
    elif t == "string":
        if not isinstance(val, str):
            raise ValueError(f"{path}: expected string")
        if "minLength" in node and len(val) < node["minLength"]:
            raise ValueError(f"{path}: string too short")
        if "maxLength" in node and len(val) > node["maxLength"]:
            raise ValueError(f"{path}: string too long")
        if "enum" in node and val not in node["enum"]:
            raise ValueError(f"{path}: {val!r} not in {node['enum']}")
        if "pattern" in node and not re.search(node["pattern"], val):
            raise ValueError(f"{path}: {val!r} does not match {node['pattern']}")
    elif t == "integer":
        if isinstance(val, bool) or not isinstance(val, int):
            raise ValueError(f"{path}: expected integer")
        if "minimum" in node and val < node["minimum"]:
            raise ValueError(f"{path}: below minimum")
        if "maximum" in node and val > node["maximum"]:
            raise ValueError(f"{path}: above maximum")
    elif t == "number":
        if isinstance(val, bool) or not isinstance(val, (int, float)):
            raise ValueError(f"{path}: expected number")
        if "minimum" in node and val < node["minimum"]:
            raise ValueError(f"{path}: below minimum")
        if "maximum" in node and val > node["maximum"]:
            raise ValueError(f"{path}: above maximum")
    elif t == "boolean":
        if not isinstance(val, bool):
            raise ValueError(f"{path}: expected boolean")


def validate_schema(data: Any, schema: dict) -> None:
    _check_node(data, schema, schema, "$")


def compute_pin(blocks_dir: Path) -> str:
    root = Path(blocks_dir).resolve()
    h = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        if (
            rel == "PIN"
            or rel.startswith("fixtures/")
            or "__pycache__" in rel
            or path.suffix == ".pyc"
        ):
            continue
        h.update(rel.encode("utf-8") + b"\0")
        h.update(path.read_bytes() + b"\0")
    return h.hexdigest()


def load_registry(blocks_dir: Path | str) -> dict:
    root = Path(blocks_dir)
    schema = json.loads((root / "registry.schema.json").read_text(encoding="utf-8"))
    reg = json.loads((root / "registry.json").read_text(encoding="utf-8"))
    validate_schema(reg, schema)

    templates_path = root / "templates.json"
    templates = json.loads(templates_path.read_text(encoding="utf-8")) if templates_path.exists() else []
    validate_schema(templates, schema)

    refusals_path = root / "refusals.json"
    refusals = json.loads(refusals_path.read_text(encoding="utf-8")) if refusals_path.exists() else {}

    merged = copy.deepcopy(reg)
    subjects = set(merged["vocab"].get("subject", ()))

    for tmpl in templates:
        subj = tmpl.get("subject")
        if subj is not None and subj not in subjects:
            raise ValueError(f"template {tmpl['id']!r} has unknown subject {subj!r}")

    packs_dir = root / "packs"
    if packs_dir.exists():
        for pack_file in sorted(packs_dir.glob("*.json")):
            pack = json.loads(pack_file.read_text(encoding="utf-8"))
            validate_schema(pack, schema)
            if pack["subject"] not in subjects:
                raise ValueError(f"pack {pack['pack']!r} has unknown subject {pack['subject']!r}")
            for kind, bspec in pack["blocks"].items():
                if kind in merged["blocks"]:
                    raise ValueError(f"duplicate block kind {kind!r} in pack {pack['pack']!r}")
                merged["blocks"][kind] = bspec

    merged["templates"] = templates
    merged["refusals"] = refusals
    return merged
