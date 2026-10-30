"""Native string resources ↔ translation JSON (L-LANG).

  python3 scripts/i18n_native.py stats
  python3 scripts/i18n_native.py export <dir> <lang...>   # <dir>/<lang>.json = {id: {en, uk}} for what values-<q> lacks
  python3 scripts/i18n_native.py import <dir> <lang...>   # <dir>/<lang>/*.json → <module>/src/main/res/values-<q>/strings_lx.xml

id = "<module>|<file stem>|<name>" for <string>, "...|<name>#<quantity>" for <plurals>,
"...|<name>[<i>]" for <string-array>. translatable="false" is skipped. Output keeps
the source file's stem (strings_money.xml → values-es/strings_money.xml) so key
sets line up file by file with values/; existing entries are kept, new ones added.
"""
import json, os, re, sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUAL = {"pt-BR": "pt", "id": "in"}
#: Quantities a language's <plurals> must carry (CLDR); others take the source's.
PLURAL_Q = {"pl": ("one", "few", "many", "other")}


def qualifier(lang): return QUAL.get(lang, lang)


def modules():
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d != "build" and not d.startswith(".")]
        if dp.endswith(os.path.join("src", "main", "res", "values")):
            yield os.path.relpath(dp, ROOT).split(os.sep + "src" + os.sep)[0], dp


def text(el):
    s = (el.text or "") + "".join(ET.tostring(c, encoding="unicode") for c in el)
    return s


def unescape(s):
    s = s.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"': s = s[1:-1]
    return s.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")


def escape(s):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if s.startswith("@") or s.startswith("?"): s = "\\" + s
    return s


def read(dirpath):
    """{(stem, id): text} for one values dir."""
    out = {}
    if not os.path.isdir(dirpath): return out
    for f in sorted(os.listdir(dirpath)):
        if not f.endswith(".xml"): continue
        try: root = ET.parse(os.path.join(dirpath, f)).getroot()
        except ET.ParseError: continue
        stem = f[:-4]
        for el in root:
            if el.get("translatable") == "false": continue
            n = el.get("name")
            if el.tag == "string": out[(stem, n)] = unescape(text(el))
            elif el.tag == "plurals":
                for it in el: out[(stem, f"{n}#{it.get('quantity')}")] = unescape(text(it))
            elif el.tag == "string-array":
                for i, it in enumerate(el): out[(stem, f"{n}[{i}]")] = unescape(text(it))
    return out


def by_name(table):
    return {k[1]: v for k, v in table.items()}


def export(outdir, langs):
    os.makedirs(outdir, exist_ok=True)
    for lang in langs:
        res = {}
        for mod, vdir in modules():
            src = read(vdir)
            if not src: continue
            uk = by_name(read(vdir + "-uk"))
            have = by_name(read(f"{vdir}-{qualifier(lang)}"))
            for (stem, n), en in src.items():
                ids = [n]
                if "#" in n:
                    base, q = n.split("#")
                    if q in ("few", "many") and lang not in PLURAL_Q: continue
                    if q == "other" and lang in PLURAL_Q:
                        ids = [f"{base}#{x}" for x in PLURAL_Q[lang] if x not in ("one",) and (stem, f"{base}#{x}") not in src or x == "other"]
                for i in ids:
                    if i in have: continue
                    res[f"{mod}|{stem}|{i}"] = {"en": en, "uk": uk.get(i, uk.get(n, en))}
        json.dump(res, open(os.path.join(outdir, f"{lang}.json"), "w"), ensure_ascii=False, indent=1)
        print(lang, len(res))


def import_(indir, langs):
    for lang in langs:
        sub = os.path.join(indir, lang)
        tr = {}
        for f in sorted(os.listdir(sub)):
            if f.endswith(".json"): tr.update(json.load(open(os.path.join(sub, f))))
        mods = dict((m, d) for m, d in modules())
        grouped = {}
        for k, v in tr.items():
            mod, stem, n = k.split("|", 2)
            grouped.setdefault((mod, stem), {})[n] = v
        for (mod, stem), items in grouped.items():
            vdir = mods[mod]
            dst_dir = f"{vdir}-{qualifier(lang)}"
            os.makedirs(dst_dir, exist_ok=True)
            dst = os.path.join(dst_dir, stem + ".xml")
            existing = {}
            if os.path.exists(dst):
                for (s, n), v in read(dst_dir).items():
                    if s == stem: existing[n] = v
            existing.update(items)
            write(dst, os.path.join(vdir, stem + ".xml"), existing, lang)
        print(lang, len(tr))


def write(dst, src_file, items, lang):
    """Values in the source file's order; plurals/arrays rebuilt whole."""
    root = ET.parse(src_file).getroot()
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for el in root:
        if el.get("translatable") == "false": continue
        n = el.get("name")
        if el.tag == "string" and n in items:
            lines.append(f'    <string name="{n}">{escape(items[n])}</string>')
        elif el.tag == "plurals":
            quantities = PLURAL_Q.get(lang) or [it.get("quantity") for it in el]
            qs = [(q, items.get(f"{n}#{q}")) for q in quantities]
            other = items.get(f"{n}#other") or items.get(f"{n}#many")
            if not any(v for _, v in qs): continue
            lines.append(f'    <plurals name="{n}">')
            seen = set()
            for q, v in qs:
                v = v or other
                if v is None or q in seen: continue
                seen.add(q)
                lines.append(f'        <item quantity="{q}">{escape(v)}</item>')
            lines.append("    </plurals>")
        elif el.tag == "string-array":
            vals = [items.get(f"{n}[{i}]") for i in range(len(el))]
            if all(v is not None for v in vals):
                lines.append(f'    <string-array name="{n}">')
                lines += [f"        <item>{escape(v)}</item>" for v in vals]
                lines.append("    </string-array>")
    lines.append("</resources>")
    open(dst, "w").write("\n".join(lines) + "\n")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "stats"
    if cmd == "export": export(sys.argv[2], sys.argv[3:])
    elif cmd == "import": import_(sys.argv[2], sys.argv[3:])
    else:
        tot = 0
        for mod, d in modules():
            n = len(read(d)); tot += n
            if n: print(n, mod)
        print("total", tot)
