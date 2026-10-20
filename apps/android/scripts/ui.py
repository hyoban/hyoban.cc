#!/usr/bin/env python3
"""Drive the app on the phone adb sees, by the names its buttons, tabs and cards go by.

    scripts/ui.py open moment/<uid>   # any moments:// link: moments, map, trips, settings,
                                      # moment/<uid>, trip/<id>, day/<YYYY-MM-DD>, place/<id>
    scripts/ui.py dump                # what's on screen: id, text, description, bounds
    scripts/ui.py tap tab_map         # a tap on the first node whose id, text or description is this
    scripts/ui.py back

The ids are the Compose test tags (tab_moments, settings, moment_<uid>, media_<uid>_<i>,
trip_<id>, day_<date>, year_<year>, back, locate, …), which the app hands uiautomator as
resource ids. ANDROID_SERIAL picks the phone when more than one is attached.
"""

import subprocess
import sys
import xml.etree.ElementTree as ET

PACKAGE = "cc.hyoban.moments"


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/window_dump.xml"))
    for node in root.iter("node"):
        a = node.attrib
        if a.get("resource-id") or a.get("text") or a.get("content-desc"):
            yield a


def centre(bounds: str) -> tuple[int, int]:
    left, top, right, bottom = (int(n) for n in bounds.replace("][", ",").strip("[]").split(","))
    return (left + right) // 2, (top + bottom) // 2


def main(argv: list[str]) -> int:
    command, *rest = argv or ["dump"]
    if command == "open":
        link = rest[0] if rest and "://" in rest[0] else f"moments://{rest[0] if rest else 'moments'}"
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", link, PACKAGE)
    elif command == "dump":
        for a in nodes():
            flags = "".join(f for f, key in (("c", "clickable"), ("s", "selected"), ("x", "checked")) if a.get(key) == "true")
            print(f"{a.get('resource-id') or '-':40} {flags:3} {a.get('bounds'):24} {a.get('text') or ''}{' | ' + a['content-desc'] if a.get('content-desc') else ''}")
    elif command == "tap":
        name = rest[0]
        found = next((a for a in nodes() if name in (a.get("resource-id"), a.get("text"), a.get("content-desc"))), None)
        if found is None:
            print(f"nothing on screen goes by {name!r}", file=sys.stderr)
            return 1
        x, y = centre(found["bounds"])
        adb("shell", "input", "tap", str(x), str(y))
    elif command == "back":
        adb("shell", "input", "keyevent", "BACK")
    else:
        print(__doc__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
