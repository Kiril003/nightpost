"""Тіла Кузні: справжній Blender без вікна, у пісочниці, доведений кадром.

Рендер — справжній (bpy/blender з /usr/local/lib/phantom-engines), команди —
через bwrap. Доказ — PNG потрібного розміру, що не є одним кольором.
"""
from __future__ import annotations

import json
import shutil
import struct
import time
import zlib

import pytest

from agent.forge.bodies import (
    BlenderBody, EngineInstall, EngineWorkspace, detect_engines, find_blender, png_verdict, prove_png,
)
from agent.forge.engine import Forge, Limits
from agent.forge.work import Workspace, verify

needs_bwrap = pytest.mark.skipif(not shutil.which("bwrap"), reason="потрібна пісочниця bwrap")

CUBE_SCENE = """
import bpy
sc = bpy.context.scene
bpy.ops.mesh.primitive_cube_add(size=2)
cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
sc.collection.objects.link(cam)
sc.camera = cam
cam.location = (5, -5, 4)
cam.rotation_euler = (1.1, 0, 0.785)
sun = bpy.data.objects.new('sun', bpy.data.lights.new('sun', 'SUN'))
sc.collection.objects.link(sun)
sun.rotation_euler = (0.6, 0.2, 0.5)
"""

# Камера є, а дивитись нема на що: ні об'єктів, ні світу — кадр чорний.
EMPTY_SCENE = """
import bpy
sc = bpy.context.scene
cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
sc.collection.objects.link(cam)
sc.camera = cam
"""

NO_CAMERA_SCENE = "import bpy\nbpy.ops.mesh.primitive_cube_add(size=2)\n"


@pytest.fixture(scope="module")
def blender() -> BlenderBody:
    inst = find_blender()
    if inst is None:
        pytest.skip("Blender (програма чи bpy) під /usr на цій машині не встановлено")
    return BlenderBody(inst)


def _png(w: int, h: int, pixel) -> bytes:
    def chunk(t: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + t + body + struct.pack(">I", zlib.crc32(t + body) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(v for x in range(w) for v in pixel(x, y)) for y in range(h))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


# ── доказ кадром: без рушія ──────────────────────────────────────────────

def test_png_proof_accepts_a_picture_and_rejects_blank_missing_wrong_size_and_broken(tmp_path):
    good = tmp_path / "good.png"
    good.write_bytes(_png(16, 16, lambda x, y: (x * 16, y * 16, 128)))
    assert prove_png(good, 16, 16).ok

    blank = tmp_path / "blank.png"
    blank.write_bytes(_png(16, 16, lambda x, y: (40, 40, 40)))
    p = prove_png(blank, 16, 16)
    assert not p.ok and "порожній" in p.detail

    assert not prove_png(tmp_path / "nope.png", 16, 16).ok
    assert "розмір" in prove_png(good, 64, 64).detail

    broken = bytearray(good.read_bytes())
    broken[40] ^= 0xFF
    (tmp_path / "broken.png").write_bytes(bytes(broken))
    assert not prove_png(tmp_path / "broken.png", 16, 16).ok

    (tmp_path / "fake.png").write_text("я кадр, чесно")
    assert not prove_png(tmp_path / "fake.png", 16, 16).ok


def test_body_refuses_engine_the_sandbox_cannot_see_and_paths_outside_workspace(blender):
    with pytest.raises(ValueError):
        BlenderBody(EngineInstall("blender", "executable", "/home/someone/blender/blender"))
    with pytest.raises(ValueError):
        BlenderBody(EngineInstall("godot", "executable", "/usr/bin/godot"))
    with pytest.raises(ValueError):
        blender.render_command("../scene.py", "out.png")
    with pytest.raises(ValueError):
        blender.render_command("scene.py", "/etc/out.png")


# ── справжній Blender ────────────────────────────────────────────────────

def test_detection_finds_a_real_blender_the_sandbox_can_see():
    blenders = [e for e in detect_engines() if e.engine == "blender"]
    if not blenders:
        pytest.skip("Blender на цій машині не встановлено")
    assert any(e.sandbox_visible and e.version for e in blenders)


@needs_bwrap
@pytest.mark.asyncio
async def test_real_headless_render_in_sandbox_is_proven_by_the_frame(tmp_path, blender):
    ws = EngineWorkspace(tmp_path)
    ws.write("scene.py", CUBE_SCENE)
    t = time.monotonic()
    proof = await verify(blender.render_check("scene.py", "render/cube.png", 64, 64), ws, None)
    took = time.monotonic() - t
    assert proof.ok and proof.strength == "executed", proof.detail
    # Кадр справді лежить у робочій теці хоста і проходить ту ж перевірку тут.
    host = prove_png(tmp_path / "render" / "cube.png", 64, 64)
    assert host.ok, host.detail
    assert took < 60, f"рендер 64×64 ішов {took:.1f} с"


@needs_bwrap
@pytest.mark.asyncio
async def test_blank_render_is_rejected(tmp_path, blender):
    ws = EngineWorkspace(tmp_path)
    ws.write("scene.py", EMPTY_SCENE)
    proof = await verify(blender.render_check("scene.py", "out.png"), ws, None)
    assert not proof.ok
    assert "порожній" in proof.detail
    # Кадр є (рендер відбувся), але він одного кольору.
    assert (tmp_path / "out.png").exists()


@needs_bwrap
@pytest.mark.asyncio
async def test_stale_frame_from_earlier_run_does_not_prove_a_broken_scene(tmp_path, blender):
    ws = EngineWorkspace(tmp_path)
    ws.write("scene.py", CUBE_SCENE)
    assert (await verify(blender.render_check("scene.py", "out.png"), ws, None)).ok
    ws.write("scene.py", NO_CAMERA_SCENE)  # зламали сцену — старий гарний кадр лишився
    proof = await verify(blender.render_check("scene.py", "out.png"), ws, None)
    assert not proof.ok and "камери" in proof.detail
    assert not (tmp_path / "out.png").exists()


@needs_bwrap
@pytest.mark.asyncio
async def test_default_sandbox_memory_cap_is_too_small_for_blender(tmp_path, blender):
    """Чому потрібен EngineWorkspace: 512 MiB адресного простору bpy не вміщає."""
    ws = Workspace(tmp_path)
    ws.write("scene.py", CUBE_SCENE)
    proof = await verify(blender.render_check("scene.py", "out.png"), ws, None)
    assert not proof.ok


@needs_bwrap
@pytest.mark.asyncio
async def test_forge_worker_builds_a_scene_and_the_render_proves_it(tmp_path, blender):
    """Досяжність: Кузня сама веде роботу, модель пише сцену, доказ — кадр."""
    prompts = []

    async def mind(system, user):
        prompts.append(user)
        return json.dumps({"actions": [{"write": {"path": "scene.py", "content": CUBE_SCENE}}]})

    mind.name = "scene-writer"
    forge = Forge(mind, EngineWorkspace(tmp_path), limits=Limits(attempts=1, max_depth=0))
    root = await forge.solve("Сцена: куб під сонцем", blender.render_check("scene.py", "shot.png", 64, 64))
    assert root.status == "done", root.failures
    assert root.proof.strength == "executed" and "PNG_OK" in root.proof.detail
    assert prove_png(tmp_path / "shot.png", 64, 64).ok


def test_real_entry_gives_forge_the_engine_and_how_to_prove_it(tmp_path):
    """Сервіс Кузні (вхід /forge/runs) бачить Blender: тека рушія + підказка моделі."""
    from agent.forge import bodies, service

    if bodies.find_blender() is None:
        pytest.skip("Blender на цій машині не видно пісочниці")
    ws, note, macros = service._engine_workspace(tmp_path / "work")
    assert isinstance(ws, bodies.EngineWorkspace) and ws.memory_limit_bytes >= 2 * 1024 ** 3
    assert "Blender" in note and '"kind": "render"' in note
    # Модель бачить перевірений довідник і кадрування системою — компактно.
    assert bodies.BlenderBody.manual() in note and "phantom_frame()" in note
    assert len(note) < 5000, len(note)
    check = macros["render"]({"kind": "render", "scene": "scene.py", "out": "render.png", "width": 64, "height": 64})
    assert check.kind == "command" and check.expect == "PNG_OK"
    with pytest.raises(ValueError):
        macros["render"]({"kind": "render", "width": 99999, "height": 64})


@pytest.mark.asyncio
async def test_examiner_names_a_render_and_the_system_builds_the_real_check(tmp_path, blender):
    """Модель не переписує довгу захищену команду (28.09 наживо це ламало JSON):
    екзаменатор каже {"kind": "render", ...}, система розгортає, рендер доводить."""
    from agent.forge import service
    from agent.forge.engine import Forge, Limits

    ws, _note, macros = service._engine_workspace(tmp_path / "work")

    class Mind:
        name = "scripted"

        async def __call__(self, system, user):
            if "examiner" in system:
                return json.dumps({"check": {"kind": "render", "scene": "scene.py", "out": "frame.png", "width": 64, "height": 64}})
            return json.dumps({"actions": [{"write": {"path": "scene.py", "content": CUBE_SCENE}}]})

    root = await Forge(Mind(), ws, check_macros=macros, limits=Limits(attempts=1, max_depth=0)).solve("Куб у Blender")
    assert root.status == "done" and root.proof.strength == "executed" and "PNG_OK" in root.proof.detail
    assert root.check.expect == "PNG_OK" and root.check_by_model
    assert (tmp_path / "work" / "frame.png").stat().st_size > 0


YARD_SCENE = """
import bpy
sc = bpy.context.scene
bpy.ops.mesh.primitive_cube_add(size=2, location=(0, 0, 1)); bpy.context.active_object.name = 'House'
bpy.ops.mesh.primitive_cylinder_add(radius=0.2, depth=2, location=(2.5, 0, 1)); bpy.context.active_object.name = 'Tree'
bpy.ops.mesh.primitive_cube_add(size=1, location=(OFF, 0, 0.5)); bpy.context.active_object.name = 'Bench'
cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
sc.collection.objects.link(cam); sc.camera = cam
cam.location = (7, -9, 6); cam.rotation_euler = (1.05, 0, 0.66)
sun = bpy.data.objects.new('sun', bpy.data.lights.new('sun', 'SUN'))
sc.collection.objects.link(sun); sun.rotation_euler = (0.6, 0.2, 0.5)
"""


@pytest.mark.asyncio
async def test_scene_audit_requires_named_objects_inside_the_frame(tmp_path, blender):
    """Кадр «не порожній» не доводить, що там будинок і дерево (28.09 наживо:
    доведений кадр з обрізаним будинком і деревом за краєм)."""
    ws = EngineWorkspace(tmp_path)
    need = ["House", "Tree", "Bench"]

    ws.write("scene.py", YARD_SCENE.replace("OFF", "-2"))
    ok = await verify(blender.render_check("scene.py", "a.png", 64, 64, require=need), ws, None)
    assert ok.ok, ok.detail

    ws.write("scene.py", YARD_SCENE.replace("OFF", "-40"))
    far = await verify(blender.render_check("scene.py", "b.png", 64, 64, require=need), ws, None)
    assert not far.ok and "«Bench» не в кадрі" in far.detail
    assert not (tmp_path / "b.png").exists()

    ws.write("scene.py", YARD_SCENE.replace("OFF", "-2"))
    missing = await verify(blender.render_check("scene.py", "c.png", 64, 64, require=need + ["Lamp"]), ws, None)
    assert not missing.ok and "у сцені нема «Lamp»" in missing.detail


def test_required_names_cannot_smuggle_code(blender):
    with pytest.raises(ValueError):
        blender.render_check("scene.py", "x.png", require=["House'); import os; os.system('x"])


# ── довідник, кадрування системою, підказка на помилці ───────────────────

# Скрипт сцени, що виконує довідник розділ за розділом саме так, як його бачить
# модель (текст manual(), розібраний назад), і записує, які розділи пройшли.
COOKBOOK_HARNESS = """
import json
done = []
for title, code in json.load(open('cookbook.json')):
    try:
        exec(compile(code, 'cookbook: ' + title, 'exec'), globals())
    except Exception as exc:
        raise SystemExit('розділ довідника «%s» не виконується: %s: %s' % (title, type(exc).__name__, exc))
    done.append(title)
json.dump(done, open('done.json', 'w'))
"""


def test_cookbook_is_python_split_into_the_sections_the_model_sees():
    from agent.forge import blender_manual

    text = BlenderBody.manual()
    compile(text, "manual", "exec")
    got = blender_manual.split_manual(text)
    assert got == blender_manual.sections() and len(got) >= 6
    for api in ("bpy.data.lights.new", "bpy.data.cameras.new", "sc.camera = cam", "Principled BSDF",
                "bpy.data.worlds.new", "from_pydata", "phantom_frame("):
        assert api in text, api
    assert "bpy.ops.light." not in text.replace("never bpy.ops.light", "")
    assert len(text) < 4000, len(text)
    # Підказки на помилці — дослівні рядки цього ж (виконуваного) довідника.
    for _trig, hint in blender_manual.hints()[len(blender_manual.RULES):]:
        head, *lines = hint.splitlines()
        assert lines and all(ln in text for ln in lines), hint


@needs_bwrap
@pytest.mark.asyncio
async def test_every_cookbook_snippet_runs_in_real_blender_and_its_scene_is_proven(tmp_path, blender):
    """Довідник не може зогнити: кожен розділ виконує справжній Blender, а
    зібрана з них сцена проходить аудит (усе назване — в кадрі) і доказ кадром."""
    from agent.forge import blender_manual

    ws = EngineWorkspace(tmp_path)
    parts = blender_manual.split_manual(BlenderBody.manual())
    ws.write("cookbook.json", json.dumps(parts, ensure_ascii=False))
    ws.write("scene.py", COOKBOOK_HARNESS)
    need = ["House", "Roof", "Tree", "Bench", "Lamp"]
    proof = await verify(blender.render_check("scene.py", "cook.png", 96, 64, require=need), ws, None)
    assert proof.ok, proof.detail[-1500:]
    assert json.loads((tmp_path / "done.json").read_text()) == [t for t, _ in parts]


# Двір, де камера дивиться в бік (як 28.09 наживо: «House» 12% у кадрі).
AIMLESS_YARD = """
import bpy
sc = bpy.context.scene
bpy.ops.mesh.primitive_cube_add(size=2, location=(0, 0, 1)); bpy.context.active_object.name = 'House'
bpy.ops.mesh.primitive_cylinder_add(radius=0.3, depth=3, location=(6, 3, 1.5)); bpy.context.active_object.name = 'Tree'
bpy.ops.mesh.primitive_cube_add(size=1, location=(-5, -4, 0.5)); bpy.context.active_object.name = 'Bench'
CAMERA
sun = bpy.data.objects.new('sun', bpy.data.lights.new('sun', 'SUN'))
sc.collection.objects.link(sun); sun.rotation_euler = (0.6, 0.2, 0.5)
FRAME
"""
_AIMED_CAMERA = ("cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))\n"
                 "sc.collection.objects.link(cam); sc.camera = cam\n"
                 "cam.location = (3, -4, 2); cam.rotation_euler = (1.3, 0, 0.9)")


@needs_bwrap
@pytest.mark.asyncio
async def test_system_framing_makes_a_badly_aimed_scene_pass_the_audit(tmp_path, blender):
    ws = EngineWorkspace(tmp_path)
    need = ["House", "Tree", "Bench"]

    ws.write("scene.py", AIMLESS_YARD.replace("CAMERA", _AIMED_CAMERA).replace("FRAME", ""))
    bad = await verify(blender.render_check("scene.py", "a.png", 64, 48, require=need), ws, None)
    assert not bad.ok and "не в кадрі" in bad.detail, bad.detail[-600:]

    # Те саме + виклик помічника системи (типово кадрує список require).
    ws.write("scene.py", AIMLESS_YARD.replace("CAMERA", _AIMED_CAMERA).replace("FRAME", "phantom_frame()"))
    good = await verify(blender.render_check("scene.py", "b.png", 64, 48, require=need), ws, None)
    assert good.ok, good.detail[-1500:]

    # Камери нема зовсім — помічник створює її сам; явний список обʼєктів.
    ws.write("scene.py", AIMLESS_YARD.replace("CAMERA", "").replace("FRAME", "phantom_frame(['House', 'Tree', 'Bench'], margin=0.2)"))
    made = await verify(blender.render_check("scene.py", "c.png", 48, 64, require=need), ws, None)
    assert made.ok, made.detail[-1500:]


@needs_bwrap
@pytest.mark.asyncio
async def test_wrong_bpy_api_fails_with_the_correct_api_from_the_cookbook(tmp_path, blender):
    """28.09 наживо Gemini кликала bpy.ops.light.add і bpy.data.cameras.add —
    падіння має саме сказати, як правильно (перевіреним розділом довідника)."""
    ws = EngineWorkspace(tmp_path)
    base = "import bpy\nsc = bpy.context.scene\nbpy.ops.mesh.primitive_cube_add(size=2)\n"

    ws.write("scene.py", base + "bpy.ops.light.add(type='SUN')\n")
    light = await verify(blender.render_check("scene.py", "a.png"), ws, None)
    assert not light.ok
    assert "скрипт сцени впав: AttributeError" in light.detail and "scene.py:4" in light.detail
    assert "bpy.data.lights.new('Sun', type='SUN')" in light.detail

    ws.write("scene.py", base + "cd = bpy.data.cameras.add('Cam')\n")
    cam = await verify(blender.render_check("scene.py", "b.png"), ws, None)
    assert not cam.ok
    assert "there is no .add" in cam.detail and "bpy.data.cameras.new('Camera')" in cam.detail

    ws.write("scene.py", base + "bpy.ops.mesh.primitive_cube_add(size=1, color=(1, 0, 0))\n")
    kw = await verify(blender.render_check("scene.py", "c.png"), ws, None)
    assert not kw.ok and "TypeError" in kw.detail and "Principled BSDF" in kw.detail


@pytest.mark.asyncio
async def test_model_sees_the_short_render_check_not_the_expanded_command(tmp_path, blender):
    """Розгорнута команда рендера — ~12 тис. символів; модель бачила її двічі в
    кожному запиті (у перевірці й в описі провалу). Тепер — коротка назва."""
    from agent.forge import service
    from agent.forge.engine import Forge
    from agent.forge.work import Task, verify

    ws, _note, macros = service._engine_workspace(tmp_path / "work")
    check = macros["render"]({"kind": "render", "scene": "scene.py", "out": "f.png", "width": 64, "height": 64, "require": ["cube"]})
    assert len(check.command) > 3000 and '"kind": "render"' in check.label

    forge = Forge(type("M", (), {"name": "m"})(), ws)
    t = forge._new(Task(goal="куб", check=check))
    prompt = forge._context(t)
    assert check.label in prompt and check.command not in prompt and len(prompt) < 6000

    ws.write("scene.py", CUBE_SCENE.replace("primitive_cube_add(size=2)", "primitive_cube_add(size=2); bpy.context.active_object.name = 'Cube'"))
    proof = await verify(check, ws, None)
    assert proof.ok and check.label in proof.detail and check.command not in proof.detail
