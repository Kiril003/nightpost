#!/usr/bin/env python3
"""Вимір брифу телефонного чату (feature-stream) зі СТАТИЧНИХ джерел.

Що міряє (символи, не токени):
  1. DEFAULT_PHANTOM — повний системний бриф (SystemPrompts.kt, raw-рядок).
  2. Скільки класів інструментів зареєстровано в buildToolRegistry.
  3. Суму літеральних `description` цих класів — вона йде в запит ДВІЧІ:
     текстом у [TOOLS: …] (ToolPromptInjector) і як нативні ToolDefinition
     (StreamTurnAssembler.nativeTools).
Обмеження: описи, зібрані шаблонами/функціями, враховано лише за літеральною
частиною; PromptBudget.trim тут НЕ емулюється — він ріже лише 4 віджети і
розділ агентів, коли в питанні нема слів-сигналів.
Запуск з кореня репо: python3 scripts/census/measure_phone_prompt.py
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2] / "phantom-companion"
SP = (ROOT / "core-ai/src/main/java/local/phantom/companion/core/ai/SystemPrompts.kt").read_text()


def raw_const(name: str) -> str:
    m = re.search(r'const val ' + name + r' = """(.*?)"""', SP, re.S)
    if not m:
        raise SystemExit(f"не знайшов {name}")
    return m.group(1)


def drop_entry(prompt: str, wid: str) -> str:
    """Порт PromptBudget.dropEntry (без dropOrders — тож результат є ВЕРХНЬОЮ межею)."""
    marker = f'"type":"{wid}"'
    at = prompt.find(marker)
    if at < 0:
        return prompt
    start = prompt.rfind("\n", 0, at)
    if start < 0:
        return prompt
    start += 1
    if not re.search(r"^\d+\.", prompt[start:at]):
        return prompt
    i = at
    while True:
        nl = prompt.find("\n", i)
        if nl < 0:
            return prompt[:start]
        ls = nl + 1
        le = prompt.find("\n", ls)
        le = len(prompt) if le < 0 else le
        line = prompt[ls:le]
        if re.search(r"^\d+\.", line) or (line.strip() and not line[0].isspace() and line.rstrip().endswith(":")):
            return prompt[:start] + prompt[ls:]
        i = ls


def drop_section(prompt: str, heading: str) -> str:
    """Порт PromptBudget.dropSection."""
    start = prompt.find(heading)
    if start < 0:
        return prompt
    lines = prompt[start:].split("\n")
    consumed = len(lines[0]) + 1
    for line in lines[1:]:
        if line.strip() and not line[0].isspace() and not line.startswith("-") \
                and not line.startswith("{") and line.rstrip().endswith(":"):
            break
        consumed += len(line) + 1
    return prompt[:start] + prompt[min(start + consumed, len(prompt)):]


def trimmed_for_plain_question(prompt: str) -> str:
    """Питання без жодного слова-сигналу («котра година»): ріжеться все, що може різатись."""
    out = prompt
    for wid in ("canvas", "photo-hero", "report", "code-runner"):
        out = drop_entry(out, wid)
    return drop_section(out, "АГЕНТИ (sub-agents):")


def desc_len(src: str, cls: str):
    i = src.find("class " + cls)
    seg = src[i:i + 20000]
    m = re.search(
        r'override val description(?::\s*String)?\s*=\s*(?:\n\s*)?'
        r'("""(.*?)"""|(?:"(?:[^"\\]|\\.)*"\s*(?:\+\s*)?\n?\s*)+)',
        seg, re.S)
    if not m:
        return None
    if m.group(2) is not None:
        return len(m.group(2))
    return sum(len(x) for x in re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)))


def main() -> None:
    full = raw_const("DEFAULT_PHANTOM")
    local = raw_const("LOCAL_COMPACT")
    print(f"DEFAULT_PHANTOM: {len(full)} символів, {len(full.encode())} байт UTF-8, {full.count(chr(10))} рядків")
    print(f"LOCAL_COMPACT:   {len(local)} символів")
    trimmed = trimmed_for_plain_question(full)
    print(f"DEFAULT_PHANTOM після PromptBudget.trim для «котра година» (верхня межа): {len(trimmed)} символів")

    fac = (ROOT / "app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt").read_text()
    body = fac[fac.index("val tools = listOf"):fac.index("return local.phantom.companion.core.tool.ToolRegistry(")]
    classes = sorted(set(re.findall(r'\b([A-Z][A-Za-z0-9]*Tool)\(', body)))
    print(f"класів інструментів у buildToolRegistry: {len(classes)}")

    srcs: dict[str, str] = {}
    for p in ROOT.glob("*/src/main/java/**/*.kt"):
        t = p.read_text(errors="ignore")
        for c in re.findall(r'\bclass ([A-Z][A-Za-z0-9]*Tool)\b', t):
            srcs.setdefault(c, t)

    total, unknown = 0, []
    for c in classes:
        n = desc_len(srcs[c], c) if c in srcs else None
        if n is None:
            unknown.append(c)
        else:
            total += n
    print(f"сума літеральних description: {total} символів "
          f"(не виміряно для {len(unknown)}: {', '.join(unknown)})")
    print(f"ОЦІНКА одного ходу без обрізання: {len(full)} (бриф) + {total} ([TOOLS]) + "
          f"{total} (нативні описи) = {len(full) + 2 * total} символів + JSON-схеми аргументів")


if __name__ == "__main__":
    main()
