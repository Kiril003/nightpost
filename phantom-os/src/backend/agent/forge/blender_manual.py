"""Довідник Blender 5 для скриптів сцени — перевірений, а не з пам'яті моделі.

28.09 наживо Gemini раз у раз кликала API, якого нема (``bpy.ops.light.add``,
``bpy.data.cameras.add``), і ставила камеру навмання («House» 12% у кадрі).
Сильна модель пам'ятає bpy краще, слабка — гірше; система не має від цього
залежати. Тому модель отримує короткий довідник, кожен рядок якого
виконується справжнім Blender у тестах (``test_forge_bodies``): довідник не
може тихо зогнити разом із новою версією Blender.

Увесь текст довідника — дійсний Python: проза в коментарях, розділи
позначені рядком ``# == Назва ==``. Розділи виконуються по черзі в одному
просторі імен (помічники першого розділу потрібні наступним).

``phantom_frame`` у тексті — не звідси: його підкладає драйвер рендеру
(``bodies._RENDER_DRIVER``), тобто довірена система, а не працівник.

``HINTS`` — коли скрипт сцени падає з AttributeError/TypeError, драйвер
додає до помилки розділ довідника, що відповідає зламаному рядку.
"""
from __future__ import annotations

import re

_HEAD = """\
# Blender 5 scene-script cookbook (verified by running it in Blender 5).
# Build the scene only: do NOT render, save or quit. Name objects after what they are;
# a required name matches any object whose name CONTAINS it ("Tree" -> Tree_Trunk, Tree_Crown).
"""

#: (назва, код, тригери помилки). Тригер — регулярний вираз по тексту
#: «Тип: повідомлення | зламаний рядок».
SECTIONS: tuple[tuple[str, str, str], ...] = (
    ("Primitives, naming, colour", """\
import bpy, math
from mathutils import Vector
sc = bpy.context.scene
def paint(o, rgba):  # rgba = (r, g, b, 1), 0..1
    m = bpy.data.materials.new(o.name + '_mat')
    m.node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value = rgba
    m.diffuse_color = rgba
    o.data.materials.append(m)
def prim(kind, name, rgba=None, **kw):  # kind: cube uv_sphere ico_sphere cylinder cone plane torus
    getattr(bpy.ops.mesh, 'primitive_%s_add' % kind)(**kw)  # kw: size|radius|depth, location, rotation, scale
    o = bpy.context.active_object
    o.name = name
    if rgba:
        paint(o, rgba)
    return o
ground = prim('plane', 'Ground', (0.25, 0.5, 0.2, 1), size=30)
""", r"primitive_\w+_add|keyword \"\w+\" unrecognized|materials?|Principled|Base Color|default_value|active_object"),
    ("Light: data + object, never bpy.ops.light", """\
sun = bpy.data.objects.new('Sun', bpy.data.lights.new('Sun', type='SUN'))  # POINT SUN SPOT AREA
sc.collection.objects.link(sun)
sun.data.energy = 3  # SUN ~3; POINT/SPOT/AREA in watts, ~500-1000
sun.rotation_euler = (math.radians(50), 0, math.radians(30))
""", r"\blights?\b|light_add|energy|SUN|POINT|SPOT|AREA"),
    ("Camera: data + object + scene.camera", """\
cam = bpy.data.objects.new('Camera', bpy.data.cameras.new('Camera'))
sc.collection.objects.link(cam)
sc.camera = cam
cam.location = (12, -14, 8)
cam.rotation_euler = (Vector((0, 0, 1)) - cam.location).to_track_quat('-Z', 'Y').to_euler()
""", r"\bcameras?\b|camera_add|lens|to_track_quat"),
    ("World (sky) colour", """\
sc.world = bpy.data.worlds.new('World')
sc.world.node_tree.nodes['Background'].inputs['Color'].default_value = (0.55, 0.7, 0.9, 1)
""", r"\bworlds?\b|Background"),
    ("Mesh from vertices: gable roof on a house", """\
def roof(name, w, d, h, z, rgba):  # ridge along Y, eaves at height z
    v = [(-w/2, -d/2, 0), (w/2, -d/2, 0), (0, -d/2, h), (-w/2, d/2, 0), (w/2, d/2, 0), (0, d/2, h)]
    f = [(0, 1, 2), (3, 5, 4), (0, 2, 5, 3), (1, 4, 5, 2), (0, 3, 4, 1)]
    me = bpy.data.meshes.new(name)
    me.from_pydata(v, [], f)
    me.update()
    o = bpy.data.objects.new(name, me)
    sc.collection.objects.link(o)
    o.location.z = z
    paint(o, rgba)
    return o
prim('cube', 'House', (0.85, 0.8, 0.7, 1), size=1, location=(0, 0, 1.5), scale=(4, 3, 3))
roof('House_Roof', 4.4, 3.4, 1.6, 3, (0.6, 0.15, 0.1, 1))
""", r"meshes|from_pydata"),
    ("Tree, bench, lamp post", """\
prim('cylinder', 'Tree_Trunk', (0.35, 0.2, 0.1, 1), radius=0.2, depth=2, location=(5, 1, 1))
prim('ico_sphere', 'Tree_Crown', (0.1, 0.45, 0.1, 1), radius=1.2, location=(5, 1, 2.8))
prim('cube', 'Bench_Seat', (0.5, 0.3, 0.15, 1), size=1, location=(3, -3, 0.45), scale=(1.6, 0.5, 0.1))
for x in (-0.7, 0.7):
    prim('cube', 'Bench_Leg', (0.2, 0.2, 0.2, 1), size=1, location=(3 + x, -3, 0.2), scale=(0.1, 0.4, 0.4))
prim('cylinder', 'Lamp_Post', (0.15, 0.15, 0.15, 1), radius=0.07, depth=3, location=(-4, -2, 1.5))
prim('uv_sphere', 'Lamp_Globe', (1, 0.95, 0.7, 1), radius=0.25, location=(-4, -2, 3.1))
bulb = bpy.data.objects.new('Lamp_Light', bpy.data.lights.new('Lamp_Light', type='POINT'))
sc.collection.objects.link(bulb)
bulb.data.energy = 300
bulb.location = (-4, -2, 3.1)
""", r"$^"),
    ("Framing: let the system aim the camera (call LAST)", """\
# phantom_frame(objects=None, margin=0.1) is provided by the render system.
# It moves the scene camera (creates one if missing) along its view direction
# so every given object fits; default = the check's "require" list, else all meshes.
phantom_frame(['House', 'Tree', 'Bench', 'Lamp'])
""", r"phantom_frame|не в кадрі"),
)


#: Короткі загальні правила: (тригер, рядок).
RULES: tuple[tuple[str, str], ...] = (
    (r'bpy_prop_collection: attribute "add"',
     "bpy.data.<collection>.new(name, ...) creates data (there is no .add); an object is "
     "bpy.data.objects.new(name, data) + sc.collection.objects.link(obj)"),
    (r'Calling operator "bpy\.ops\.[\w.]+" error, could not be found',
     "that operator does not exist in Blender 5; build data with bpy.data.*.new (see below)"),
    (r'keyword "\w+" unrecognized',
     "operators take only their own keywords (primitive_*_add: size/radius/depth, location, rotation, scale); "
     "colour goes through a material"),
)


def sections() -> list[tuple[str, str]]:
    """Розділи довідника як (назва, код) — саме те, що бачить модель."""
    return [(title, code) for title, code, _ in SECTIONS]


def manual() -> str:
    """Весь довідник одним текстом (дійсний Python)."""
    return _HEAD + "".join(f"# == {title} ==\n{code}" for title, code, _ in SECTIONS)


def split_manual(text: str) -> list[tuple[str, str]]:
    """Розібрати текст довідника назад на розділи (для тесту: виконуємо те, що бачить модель)."""
    parts = re.split(r"^# == (.+?) ==\n", text, flags=re.M)
    return [(parts[i], parts[i + 1]) for i in range(1, len(parts) - 1, 2)]


#: Які рядки розділу потрапляють у підказку (за підрядком). Підказка — витяг
#: дослівних рядків довідника, а не весь розділ: драйвер вшито в команду, а
#: команду модель бачить у перевірці й у тексті провалу — кожен знак на рахунку.
_HINT_LINES: dict[str, tuple[str, ...]] = {
    "Primitives, naming, colour": ("materials.new", "Base Color", "materials.append", "getattr(bpy.ops.mesh", "o.name = name"),
    "Light: data + object, never bpy.ops.light": ("lights.new", ".link(sun)", "energy"),
    "Camera: data + object + scene.camera": ("cameras.new", ".link(cam)", "sc.camera", "to_track_quat"),
    "World (sky) colour": ("worlds.new", "Background"),
    "Mesh from vertices: gable roof on a house": ("meshes.new", "from_pydata", "objects.new(name, me)", ".link(o)"),
    "Framing: let the system aim the camera (call LAST)": ("phantom_frame(objects", "phantom_frame(["),
}


def hints() -> list[list[str]]:
    """[[тригер, підказка], ...] для драйвера рендеру (вшивається в команду JSON-ом).

    Спершу короткі правила, далі витяги з розділів; драйвер бере перші збіги.
    """
    out = [[t, "# " + h] for t, h in RULES]
    for title, code, trig in SECTIONS:
        keys = _HINT_LINES.get(title)
        if keys:
            lines = [ln.strip() for ln in code.splitlines() if any(k in ln for k in keys)]
            out.append([trig, f"# {title}:\n" + "\n".join(lines)])
    return out
