"""Двійник функції в модулі мовчки перекриває першу: правиш одну — працює інша."""
import ast
from collections import Counter
from pathlib import Path


def test_tool_executor_defines_each_function_once():
    tree = ast.parse((Path(__file__).resolve().parents[1] / "ai" / "tool_executor.py").read_text(encoding="utf-8"))
    names = Counter(n.name for n in tree.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)))
    assert [n for n, k in names.items() if k > 1] == []
