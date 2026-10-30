"""Тіла Кузні: справжні рушії (Blender, Godot, Unity, Unreal) на цій машині.

Правило те саме, що й у всієї Кузні: «сцену зібрано» — це не слово моделі,
а виконана перевірка. Для Blender доказ — кадр, відрендерений у пісочниці
без вікна, і PNG, що має очікуваний розмір і не є одним кольором.

Що тут є:
- ``detect_engines()`` — які рушії справді стоять (Blender як програма або
  як модуль ``bpy``; Godot/Unity/Unreal — лише виявлення, без запуску);
- ``BlenderBody`` — будує рядок команди для ``Workspace.run``: скрипт сцени
  з робочої теки + рендер одного кадру в PNG;
- ``png_verdict`` / ``prove_png`` — перевірка кадру голою stdlib (zlib/struct),
  і та сама функція вшивається в команду перевірки, що виконується в пісочниці.

Пісочниця (``SandboxProfile.compute``) бачить лише ``/usr`` і ``/etc`` на
читання та робочу теку на ``/workspace``. Тому рушій придатний, лише якщо
лежить під ``/usr`` (напр. ``/usr/local/lib/phantom-engines``) — прапорець
``sandbox_visible``. Жодних нових монтувань тут нема.

Пам'ять: ``wrap_argv`` за замовчуванням ставить ``prlimit --as=512 MiB``, а
Blender 5 лише щоб завантажити ``bpy`` резервує ~1,75 GiB адресного простору
(«libusd_ms.so: failed to map segment»). ``EngineWorkspace`` — та сама
пісочниця, той самий профіль, лише явна вища стеля адресного простору.
"""
from __future__ import annotations

import glob
import inspect
import json
import os
import re
import shlex
import shutil
import subprocess
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal

from agent.forge import blender_manual
from agent.forge.work import Check, Proof, Workspace

EngineName = Literal["blender", "godot", "unity", "unreal"]

#: Куди ставимо рушії, щоб пісочниця бачила їх без нових монтувань (/usr — ro-bind).
ENGINES_ROOT = "/usr/local/lib/phantom-engines"

#: Що пісочниця compute монтує на читання з хоста (див. _BWRAP_BASE_FLAGS).
_SANDBOX_RO_ROOTS = ("/usr", "/etc")

#: Стеля адресного простору для рушіїв. Виміряно 28.09: bpy 5.0.1 не
#: імпортується при 1,5 GiB, рендерить при 1,75 GiB; 4 GiB — із запасом на сцени.
ENGINE_MEMORY_LIMIT = 4 * 1024 ** 3

#: Інтерпретатори, якими пробуємо модуль bpy (усі під /usr — видно в пісочниці).
_PYTHON_CANDIDATES = ("/usr/local/bin/python3", "/usr/bin/python3", "/usr/local/bin/python3.11", "/usr/bin/python3.11")

_RENDER_ENGINES = ("CYCLES", "BLENDER_WORKBENCH", "BLENDER_EEVEE")  # Blender 5 не знає BLENDER_EEVEE_NEXT


@dataclass(frozen=True)
class EngineInstall:
    engine: EngineName
    kind: Literal["executable", "bpy-module"]
    path: str                     # програма або тека, в якій лежить пакет bpy
    python: str = ""              # для bpy-module: інтерпретатор, яким він імпортується
    version: str = ""             # лише для Blender (його ми справді запускаємо)

    @property
    def sandbox_visible(self) -> bool:
        return _under_sandbox_roots(self.path) and (not self.python or _under_sandbox_roots(self.python))


def _under_sandbox_roots(path: str) -> bool:
    real = os.path.realpath(path)
    return any(real == r or real.startswith(r + "/") for r in _SANDBOX_RO_ROOTS)


# ── виявлення ────────────────────────────────────────────────────────────

def _probe(argv: list[str], timeout: float = 90.0) -> str | None:
    try:
        r = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return r.stdout if r.returncode == 0 else None


@lru_cache(maxsize=32)
def _bpy_version(python: str, bpy_dir: str) -> str | None:
    code = f"import sys; sys.path.insert(0, {bpy_dir!r}); import bpy; print('BPY', bpy.app.version_string)"
    out = _probe([python, "-c", code])
    for line in (out or "").splitlines():
        if line.startswith("BPY "):
            return line[4:].strip()
    return None


@lru_cache(maxsize=32)
def _blender_version(exe: str) -> str | None:
    out = _probe([exe, "-b", "--factory-startup", "--version"])
    for line in (out or "").splitlines():
        if line.startswith("Blender "):
            return line.split()[1]
    return None


def _existing(paths: list[str]) -> list[str]:
    seen: list[str] = []
    for p in paths:
        if p and os.path.isfile(p) and os.access(p, os.X_OK):
            real = os.path.realpath(p)
            if real not in [os.path.realpath(s) for s in seen]:
                seen.append(p)
    return seen


def _which(*names: str) -> list[str]:
    return [p for p in (shutil.which(n) for n in names) if p]


def detect_engines(root: str = ENGINES_ROOT) -> list[EngineInstall]:
    """Рушії, що справді є на цій машині.

    Blender підтверджуємо запуском (версія), бо саме його Кузня запускає;
    Godot/Unity/Unreal — лише за наявністю виконуваного файлу.
    """
    home = os.path.expanduser("~")
    found: list[EngineInstall] = []

    for exe in _existing(_which("blender") + sorted(glob.glob(f"{root}/blender*/blender")) + sorted(glob.glob("/opt/blender*/blender"))):
        ver = _blender_version(exe)
        if ver:
            found.append(EngineInstall("blender", "executable", exe, version=ver))

    for bpy_dir in sorted(glob.glob(f"{root}/bpy*")):
        if not os.path.isdir(os.path.join(bpy_dir, "bpy")):
            continue
        for py in _existing(list(_PYTHON_CANDIDATES)):
            ver = _bpy_version(py, bpy_dir)
            if ver:
                found.append(EngineInstall("blender", "bpy-module", bpy_dir, python=py, version=ver))
                break

    for exe in _existing(_which("godot", "godot4", "godot3") + sorted(glob.glob(f"{root}/godot*/godot*")) + sorted(glob.glob("/opt/godot*/godot*"))):
        found.append(EngineInstall("godot", "executable", exe))

    for exe in _existing(
        _which("unity-editor", "Unity")
        + sorted(glob.glob(f"{home}/Unity/Hub/Editor/*/Editor/Unity"))
        + sorted(glob.glob("/opt/unity*/Editor/Unity"))
        + sorted(glob.glob(f"{root}/unity*/Editor/Unity"))
    ):
        found.append(EngineInstall("unity", "executable", exe))

    for exe in _existing(
        _which("UnrealEditor", "UnrealEditor-Cmd")
        + sorted(glob.glob("/opt/UnrealEngine*/Engine/Binaries/Linux/UnrealEditor*"))
        + sorted(glob.glob(f"{home}/UnrealEngine*/Engine/Binaries/Linux/UnrealEditor*"))
        + sorted(glob.glob(f"{root}/UnrealEngine*/Engine/Binaries/Linux/UnrealEditor*"))
    ):
        found.append(EngineInstall("unreal", "executable", exe))

    return found


def find_blender(root: str = ENGINES_ROOT) -> EngineInstall | None:
    """Перший Blender, який пісочниця бачить (програма краща за модуль)."""
    usable = [e for e in detect_engines(root) if e.engine == "blender" and e.sandbox_visible]
    usable.sort(key=lambda e: e.kind != "executable")
    return usable[0] if usable else None


# ── доказ кадром ─────────────────────────────────────────────────────────

def png_verdict(path: str, width: int, height: int, min_colours: int = 4, min_spread: int = 16) -> dict:
    """Чи є ``path`` справжнім кадром: PNG, розмір width×height, не один колір.

    Самодостатня (імпорти всередині): її текст вшивається в команду
    перевірки, яку Кузня виконує в пісочниці, де нашого пакета нема.
    """
    import struct
    import zlib

    def bad(reason: str) -> dict:
        return {"ok": False, "reason": reason}

    try:
        with open(path, "rb") as fh:
            data = fh.read()
    except OSError as exc:
        return bad(f"кадру нема: {exc.strerror or exc}")
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return bad("це не PNG")
    pos, ihdr, idat, palette, ended = 8, None, [], b"", False
    while pos + 8 <= len(data):
        length, ctype = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        crc = data[pos + 8 + length:pos + 12 + length]
        if len(body) != length or len(crc) != 4 or struct.unpack(">I", crc)[0] != zlib.crc32(ctype + body) & 0xFFFFFFFF:
            return bad(f"PNG пошкоджено біля {ctype!r}")
        if ctype == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif ctype == b"PLTE":
            palette = body
        elif ctype == b"IDAT":
            idat.append(body)
        elif ctype == b"IEND":
            ended = True
            break
        pos += 12 + length
    if ihdr is None or not idat or not ended:
        return bad("PNG неповний")
    w, h, depth, ctype_, _, _, interlace = ihdr
    if (w, h) != (width, height):
        return bad(f"розмір {w}×{h}, а чекали {width}×{height}")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(ctype_)
    if channels is None or depth not in (8, 16) or interlace:
        return bad(f"формат PNG не підтримано (тип {ctype_}, глибина {depth}, черезрядковий {interlace})")
    bpp = channels * depth // 8
    stride = w * bpp
    try:
        raw = zlib.decompress(b"".join(idat))
    except zlib.error as exc:
        return bad(f"PNG не розпаковується: {exc}")
    if len(raw) != h * (stride + 1):
        return bad("довжина даних не відповідає розміру")
    prev = bytearray(stride)
    colours: set = set()
    lo = [255] * 4
    hi = [0] * 4
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
            elif f != 0:
                return bad(f"невідомий фільтр рядка {f}")
        prev = line
        step = depth // 8  # для 16 біт беремо старший байт
        for x in range(w):
            px = tuple(line[x * bpp + k * step] for k in range(channels))
            if ctype_ == 3:
                i3 = px[0] * 3
                px = tuple(palette[i3:i3 + 3])
            if len(colours) < 4096:
                colours.add(px)
            for k, v in enumerate(px):
                lo[k] = min(lo[k], v)
                hi[k] = max(hi[k], v)
    spread = max(hi[k] - lo[k] for k in range(4))
    if len(colours) < min_colours or spread < min_spread:
        return bad(f"кадр порожній: {len(colours)} кольорів, розкид {spread} (треба ≥{min_colours} і ≥{min_spread})")
    return {"ok": True, "reason": f"кадр {w}×{h}, {len(colours)}{'+' if len(colours) >= 4096 else ''} кольорів, розкид {spread}", "colours": len(colours), "spread": spread}


def prove_png(path: Path | str, width: int, height: int, **kw: Any) -> Proof:
    v = png_verdict(str(path), width, height, **kw)
    return Proof(bool(v["ok"]), "executed", str(v["reason"]))


def _png_check_code(out: str, width: int, height: int) -> str:
    src = inspect.getsource(png_verdict)
    return (
        f"{src}\nimport sys\nv = png_verdict({out!r}, {int(width)}, {int(height)})\n"
        "print(('PNG_OK ' if v['ok'] else 'PNG_BAD ') + v['reason'])\nsys.exit(0 if v['ok'] else 1)\n"
    )


# ── тіло Blender ─────────────────────────────────────────────────────────

# Драйвер рендеру вшито в команду (-c / --python-expr), а не покладено файлом у
# робочу теку: працівник пише в ту теку і міг би підмінити драйвер. Старий кадр
# стирається до рендеру — доказ завжди з цього запуску.
#
# Драйвер дає скрипту сцени довірений помічник ``phantom_frame`` (у globals до
# runpy): камеру ставить система, а не вгадує модель. А коли скрипт падає на
# неіснуючому API (AttributeError/TypeError), до помилки додається розділ
# перевіреного довідника (``blender_manual``), що відповідає зламаному рядку.
_RENDER_DRIVER_TEMPLATE = """
import os, re, runpy, sys
argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else sys.argv[1:]
scene, out, w, h, engine, samples = argv[:6]
import json
require = json.loads(argv[6]) if len(argv) > 6 else []
HINTS = json.loads(__HINTS__)
KINDS = ('MESH', 'CURVE', 'SURFACE', 'META', 'FONT', 'LIGHT')
out = os.path.abspath(out)
if os.path.exists(out):
    os.remove(out)
import bpy
bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
# Роздільність — до сцени: від пропорцій кадру залежить, що в нього влазить.
sc.render.resolution_x, sc.render.resolution_y, sc.render.resolution_percentage = int(w), int(h), 100


def _points(obs):
    from mathutils import Vector
    dg = bpy.context.evaluated_depsgraph_get()
    pts = []
    for o in obs:
        if o.type == 'LIGHT':
            pts.append(o.matrix_world.translation.copy())
        else:
            ob = o.evaluated_get(dg)
            pts += [ob.matrix_world @ Vector(c) for c in ob.bound_box]
    return pts


def phantom_frame(objects=None, margin=0.1):
    # Поставити камеру сцени (або створити) так, щоб усі objects були в кадрі:
    # напрям погляду камери лишається, система відсуває її вздовж нього.
    from mathutils import Matrix, Vector
    sc = bpy.context.scene
    bpy.context.view_layer.update()
    if objects is None:
        objects = list(require) or [o for o in sc.objects if o.type == 'MESH']
    obs = []
    for x in objects:
        if isinstance(x, str):
            obs += [o for o in sc.objects if x.lower() in o.name.lower() and o.type in KINDS]
        else:
            obs.append(x)
    if not obs:
        raise ValueError('phantom_frame: у сцені нема обʼєктів %r' % (objects,))
    pts = _points(obs)
    lo = Vector([min(p[i] for p in pts) for i in range(3)])
    hi = Vector([max(p[i] for p in pts) for i in range(3)])
    c = (lo + hi) / 2
    cam = sc.camera
    if cam is None:
        cam = bpy.data.objects.new('Camera', bpy.data.cameras.new('Camera'))
        sc.collection.objects.link(cam)
        sc.camera = cam
        rot = Vector((-1.0, 1.3, -0.75)).to_track_quat('-Z', 'Y')
    else:
        rot = cam.matrix_world.to_quaternion()
    f, r, u = rot @ Vector((0, 0, -1)), rot @ Vector((1, 0, 0)), rot @ Vector((0, 1, 0))
    k = max(0.05, 1.0 - float(margin))
    frame = cam.data.view_frame(scene=sc)
    qs = [p - c for p in pts]
    near = cam.data.clip_start * 2
    if cam.data.type == 'ORTHO':
        tx, ty = max(abs(v.x) for v in frame), max(abs(v.y) for v in frame)
        grow = max(max(abs(q.dot(r)) / (tx * k) for q in qs), max(abs(q.dot(u)) / (ty * k) for q in qs), 1e-6)
        cam.data.ortho_scale *= grow
        d = max(near - q.dot(f) for q in qs) + 1.0
    else:
        tx = max(abs(v.x / v.z) for v in frame)
        ty = max(abs(v.y / v.z) for v in frame)
        d = max(max(abs(q.dot(r)) / (tx * k), abs(q.dot(u)) / (ty * k)) - q.dot(f) for q in qs)
        d = max(d, max(near - q.dot(f) for q in qs))
    cam.matrix_world = Matrix.Translation(c - f * d) @ rot.to_matrix().to_4x4()
    span = max(q.length for q in qs)
    cam.data.clip_end = max(cam.data.clip_end, d + 2 * span + 1)
    bpy.context.view_layer.update()
    return cam


try:
    runpy.run_path(scene, init_globals={'phantom_frame': phantom_frame}, run_name='__main__')
except (AttributeError, TypeError) as exc:
    import traceback
    frames = [fr for fr in traceback.extract_tb(exc.__traceback__) if os.path.abspath(fr.filename) == os.path.abspath(scene)]
    where = ('%s:%d: %s' % (scene, frames[-1].lineno, frames[-1].line)) if frames else scene
    said = '%s: %s' % (type(exc).__name__, exc)
    text = '%s | %s' % (said, where)
    fix, size = [], 0
    for rx, hint in HINTS:
        if re.search(rx, text) and size + len(hint) <= 1000:
            fix.append(hint)
            size += len(hint)
    msg = 'скрипт сцени впав: %s\\n  у %s' % (said, where)
    if fix:
        msg += '\\nПравильний API Blender 5 (перевірено):\\n' + '\\n'.join(fix)
    sys.exit(msg)
sc = bpy.context.scene
if sc.camera is None:
    sys.exit('у сцені нема камери — кадру не буде')
# Аудит до кадру: кожен обов'язковий об'єкт є і справді в кадрі камери.
# Кадр «не порожній» не каже, чи там будинок, а не обрізаний край даху
# (28.09 наживо: доведений кадр двору з обрізаним будинком і деревом).
if require:
    from bpy_extras.object_utils import world_to_camera_view
    sc.render.resolution_x, sc.render.resolution_y = int(w), int(h)
    for token in require:
        hits = [o for o in sc.objects if token.lower() in o.name.lower() and o.type in KINDS]
        if not hits:
            sys.exit('у сцені нема «%s» (обʼєкти: %s)' % (token, ', '.join(sorted(o.name for o in sc.objects))[:400]))
        best = 0.0
        for o in hits:
            pts = _points([o])
            inside = 0
            for p in pts:
                v = world_to_camera_view(sc, sc.camera, p)
                if v.z > 0 and 0.0 <= v.x <= 1.0 and 0.0 <= v.y <= 1.0:
                    inside += 1
            best = max(best, inside / len(pts))
        if best < 0.75:
            sys.exit('«%s» не в кадрі: у кадр потрапляє %d%% його габаритів (треба ≥75%%)' % (token, int(best * 100)))
    print('AUDIT_OK', ', '.join(require))
r = sc.render
r.engine = engine
r.resolution_x, r.resolution_y, r.resolution_percentage = int(w), int(h), 100
r.image_settings.file_format = 'PNG'
r.image_settings.color_mode = 'RGB'
r.image_settings.color_depth = '8'
if engine == 'CYCLES':
    sc.cycles.device = 'CPU'
    sc.cycles.samples = int(samples)
r.filepath = out
bpy.ops.render.render(write_still=True)
print('RENDER_OK', out)
"""

_RENDER_DRIVER = _RENDER_DRIVER_TEMPLATE.replace("__HINTS__", repr(json.dumps(blender_manual.hints(), ensure_ascii=False)))


def _safe_rel(rel: str) -> str:
    p = os.path.normpath(rel)
    if not rel or os.path.isabs(rel) or p == ".." or p.startswith("../"):
        raise ValueError(f"шлях має бути всередині робочої теки: {rel!r}")
    return p


class BlenderBody:
    """Blender без вікна в пісочниці Кузні: скрипт сцени → кадр PNG → доказ."""

    def __init__(self, install: EngineInstall) -> None:
        if install.engine != "blender":
            raise ValueError(f"це не Blender: {install.engine}")
        if not install.sandbox_visible:
            raise ValueError(f"пісочниця не бачить {install.path}: рушій має лежати під /usr (напр. {ENGINES_ROOT})")
        self.install = install

    @staticmethod
    def manual() -> str:
        """Перевірений довідник скриптів сцени (див. ``blender_manual``)."""
        return blender_manual.manual()

    def _python_argv(self, code: str, args: list[str]) -> list[str]:
        if self.install.kind == "executable":
            return [self.install.path, "-b", "--factory-startup", "-noaudio", "--python-exit-code", "1", "--python-expr", code, "--", *args]
        prelude = f"import sys; sys.path.insert(0, {self.install.path!r})\n"
        return [self.install.python, "-c", prelude + code, *args]

    def script_command(self, script_rel: str, *args: str) -> str:
        """Виконати Python-скрипт робочої теки всередині Blender."""
        code = "import runpy, sys\nargv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else sys.argv[1:]\nsys.argv = argv\nrunpy.run_path(argv[0], run_name='__main__')\n"
        return shlex.join(self._python_argv(code, [_safe_rel(script_rel), *args]))

    def render_command(self, scene_rel: str, out_rel: str, width: int = 64, height: int = 64, *, engine: str = "CYCLES", samples: int = 8,
                       require: list[str] | None = None) -> str:
        """Зібрати сцену скриптом ``scene_rel`` і відрендерити кадр у ``out_rel``.

        CYCLES на CPU — типово: Workbench/EEVEE без GPU йдуть через програмний
        EGL (виміряно: 6 с і 68 с на 64×64 проти ~1 с у Cycles).
        """
        if engine not in _RENDER_ENGINES:
            raise ValueError(f"невідомий рушій рендеру: {engine}")
        if not (0 < width <= 8192 and 0 < height <= 8192):
            raise ValueError("розмір кадру поза межами")
        args = [_safe_rel(scene_rel), _safe_rel(out_rel), str(int(width)), str(int(height)), engine, str(int(samples))]
        need = [str(x) for x in (require or [])]
        if len(need) > 24 or any(not re.fullmatch(r"[\w .-]{1,40}", x) for x in need):
            raise ValueError("обов'язкові обʼєкти: до 24 назв, літери/цифри/пробіл/._-")
        if need:
            args.append(json.dumps(need, ensure_ascii=False))
        return shlex.join(self._python_argv(_RENDER_DRIVER, args))

    def render_check(self, scene_rel: str, out_rel: str, width: int = 64, height: int = 64, **kw: Any) -> Check:
        """Перевірка Кузні: рендер цього запуску + доказ кадру, все в пісочниці."""
        render = self.render_command(scene_rel, out_rel, width, height, **kw)
        # Системний python3 під /usr — перевірка не залежить від модуля bpy.
        prove = shlex.join(["python3", "-c", _png_check_code(_safe_rel(out_rel), width, height)])
        return Check(kind="command", command=f"{render} && {prove}", expect="PNG_OK")


class EngineWorkspace(Workspace):
    """Робоча тека для рушіїв: та сама пісочниця compute, вища стеля пам'яті."""

    def __init__(self, root: Path, *, memory_limit_bytes: int = ENGINE_MEMORY_LIMIT, **kw: Any) -> None:
        super().__init__(root, memory_limit_bytes=int(memory_limit_bytes), **kw)
