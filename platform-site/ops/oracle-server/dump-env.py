"""Працює на машині Fly: друкує NAME='значення' для названих змінних, які там задано.

Одинарні лапки, бо env_file у compose підставляє $ у значеннях без лапок і в подвійних.
Значення, яке в одинарні лапки не покласти (є ' чи перенос рядка, або кінець на \\),
не друкується: у stderr лишається лише ім'я, і власник додає його вручну.
"""
import os
import sys

skipped = []
for name in sys.argv[1:]:
    value = os.environ.get(name, "")
    if not value:
        continue
    if "'" in value or "\n" in value or "\r" in value or value.endswith("\\"):
        skipped.append(name)
        continue
    print(f"{name}='{value}'")
if skipped:
    print("set by hand, cannot be single-quoted: " + " ".join(skipped), file=sys.stderr)
