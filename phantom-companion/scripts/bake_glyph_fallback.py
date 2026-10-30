#!/usr/bin/env python3
"""Порожній, але валідний .pbf для кожного діапазону гліфів, якого нема в пакунку.

Діапазон, що не читається, MapLibre не вважає порожнім: тайл чекає на нього
вічно й не малює НІЧОГО — ні доріг, ні будинків, ні підписів. Одного «№» у
назві аптеки досить, щоб квартал зник. Порожній діапазон — це лише знак без
гліфа, решта тайла малюється.
"""
import pathlib
import sys

FONTS = pathlib.Path(__file__).resolve().parent.parent / "feature-stream/src/main/assets/fonts"
STACK = "Noto Sans Regular"


def field(tag: int, payload: bytes) -> bytes:
    return bytes([tag << 3 | 2, len(payload)]) + payload


def empty_range(lo: int) -> bytes:
    rng = f"{lo}-{lo + 255}".encode()
    return field(1, field(1, STACK.encode()) + field(2, rng))


def main() -> int:
    made = 0
    for spelling in (STACK, STACK.replace(" ", "%20")):
        d = FONTS / spelling
        for lo in range(0, 65536, 256):
            f = d / f"{lo}-{lo + 255}.pbf"
            if not f.exists():
                f.write_bytes(empty_range(lo))
                made += 1
    print(f"порожніх діапазонів додано: {made}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
