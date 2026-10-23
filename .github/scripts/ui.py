#!/usr/bin/env python3
"""Tiny helper to drive the app on the CI emulator: find things on screen by text and tap them."""
import re
import subprocess
import sys
import time

def adb(*args, check=False):
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=check).stdout

def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("shell", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in xml:
            return xml
        time.sleep(1)
    return ""

NODE = re.compile(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

def nodes():
    out = []
    for m in NODE.finditer(dump()):
        text, desc, x1, y1, x2, y2 = m.groups()
        out.append((text, desc, int(x1), int(y1), int(x2), int(y2)))
    return out

def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))

FALLBACK = None

def tap_match(pred, label, index=0):
    all_nodes = nodes()
    found = [n for n in all_nodes if pred(n)]
    if len(found) <= index:
        print(f"NOT FOUND: {label} (screen has {len(all_nodes)} nodes)")
        with open("shots/ui-dumps.txt", "a") as f:
            f.write(f"\n===== looking for {label}\n")
            for n in all_nodes:
                if n[0] or n[1]:
                    f.write(repr(n) + "\n")
        if FALLBACK:
            print(f"fallback tap at {FALLBACK}")
            tap(*FALLBACK)
        return False
    t, d, x1, y1, x2, y2 = found[index]
    print(f"tap {label} -> '{t or d}' at {(x1 + x2)//2},{(y1 + y2)//2}")
    tap((x1 + x2) // 2, (y1 + y2) // 2)
    return True

def main():
    global FALLBACK
    args = sys.argv[1:]
    if "--at" in args:
        i = args.index("--at")
        FALLBACK = (int(args[i + 1]), int(args[i + 2]))
        args = args[:i] + args[i + 3:]
    cmd, *rest = args
    if cmd == "text":
        ok = tap_match(lambda n: n[0] == rest[0] or n[1] == rest[0], rest[0], int(rest[1]) if len(rest) > 1 else 0)
    elif cmd == "contains":
        ok = tap_match(lambda n: rest[0].lower() in (n[0] + " " + n[1]).lower(), rest[0], int(rest[1]) if len(rest) > 1 else 0)
    elif cmd == "cover":
        # First picture with a description (a book cover) below the top bars.
        min_y = int(rest[0]) if rest else 350
        ok = tap_match(lambda n: n[1] and not n[0] and n[3] > min_y and (n[5] - n[3]) > 150, "first cover")
    elif cmd == "list":
        for n in nodes():
            if n[0] or n[1]:
                print(n)
        ok = True
    else:
        print("unknown command", cmd)
        ok = False
    sys.exit(0)

main()
