#!/usr/bin/env python3
"""Capture the Zeekr app's car, charging, trip and assisted-driving data over adb.

Drives com.zeekrlife.mobile on a phone that `adb devices` sees, reads each page
through `uiautomator dump`, and writes one JSON snapshot. The trip report's
daily bars are read by tapping them, since their values only exist in the
tooltip the chart shows on tap.

Usage:
  zeekr_capture.py [--out DIR] [--sections car,energy,charging,trips,assist]
                   [--months N] [--serial SERIAL]
"""

import argparse
import copy
import datetime as dt
import glob
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.zeekrlife.mobile"
SERIAL = None
W, H = 1272, 2772  # replaced by the device's size at start
REF_W = 1272  # the phone the layout offsets were measured on


def log(*a):
    print(*a, file=sys.stderr, flush=True)


# Seconds and call counts per kind of adb call, printed with --profile.
PROFILE = {}
_sleep = time.sleep


def _timed_sleep(s):
    t = time.time()
    _sleep(s)
    k = PROFILE.setdefault("sleep", [0, 0.0])
    k[0] += 1
    k[1] += time.time() - t


time.sleep = _timed_sleep


def adb(*args, text=True):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    t = time.time()
    out = subprocess.run(cmd, capture_output=True)
    kind = "dump" if "dump" in args else args[2] if args[:2] == ("shell", "input") else "other"
    k = PROFILE.setdefault(kind, [0, 0.0])
    k[0] += 1
    k[1] += time.time() - t
    return out.stdout.decode("utf-8", "replace") if text else out.stdout


def px(v):
    """Scale a length measured on the 1272px-wide reference screen."""
    return int(round(v * W / REF_W))


def tap(x, y, wait=0.0):
    adb("shell", "input", "tap", str(int(x)), str(int(y)))
    if wait:
        time.sleep(wait)


def back(wait=2.0):
    adb("shell", "input", "keyevent", "BACK")
    time.sleep(wait)


def drag(y1, y2, ms=800, x=None):
    """A slow drag scrolls by about y1 - y2 without flinging."""
    x = W // 2 if x is None else x
    adb("shell", "input", "swipe", str(x), str(int(y1)), str(x), str(int(y2)), str(ms))


class Node:
    def __init__(self, el):
        self.text = norm(el.get("text", ""))
        self.desc = norm(el.get("content-desc", ""))
        self.class_name = el.get("class", "")
        b = re.findall(r"\d+", el.get("bounds", "[0,0][0,0]"))
        self.x1, self.y1, self.x2, self.y2 = map(int, b)

    @property
    def cx(self):
        return (self.x1 + self.x2) // 2

    @property
    def cy(self):
        return (self.y1 + self.y2) // 2

    def __repr__(self):
        return f"<{self.text!r} {self.x1},{self.y1},{self.x2},{self.y2}>"


def norm(s):
    # Icons are icon-font glyphs in the Private Use Area; drop them.
    s = re.sub("[-]", "", s)
    return s.replace("\xa0", " ").replace("&#10;", "\n").strip()


def dump():
    for _ in range(3):
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        end = raw.rfind("</hierarchy>")
        if end != -1:
            root = ET.fromstring(raw[: end + len("</hierarchy>")])
            return [Node(el) for el in root.iter("node")]
        time.sleep(0.5)
    raise RuntimeError("uiautomator dump failed")


def texts(nodes):
    return [n.text for n in nodes if n.text]


def find(nodes, pattern, exact=False):
    for n in nodes:
        if (n.text == pattern) if exact else re.search(pattern, n.text):
            return n
    return None


def settle(anchor, timeout=15.0, still=lambda t: True):
    """Dump until `anchor` shows and the page text stops changing.

    Pages fill in after they open (the trip page first shows 0 km), so a page
    counts as loaded once two dumps in a row match and `still` accepts them.
    """
    deadline = time.time() + timeout
    prev = None
    while time.time() < deadline:
        nodes = dump()
        t = texts(nodes)
        if find(nodes, anchor) and t == prev and still(t):
            return nodes
        prev = t
        time.sleep(0.8)
    log(f"warning: page with {anchor!r} did not settle")
    return dump()


def top_activity():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=.*? (\S+/\S+)", out)
    return m.group(1) if m else ""


def num(s):
    m = re.search(r"-?\d+(?:\.\d+)?", s or "")
    if not m:
        return None
    v = float(m.group(0))
    return int(v) if v.is_integer() and "." not in m.group(0) else v


def before(t, label, skip_units=True):
    """The value shown above a label, skipping a separate unit token."""
    if label not in t:
        return None
    i = t.index(label) - 1
    # Values start with a digit; units like "kWh/100km" only contain one.
    while skip_units and i > 0 and not re.match(r"[-+]?\d", t[i]):
        i -= 1
    return t[i] if i >= 0 else None


# ---------------------------------------------------------------- car page

def open_car_page():
    if not top_activity().startswith(PACKAGE):
        adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(4)
    # Back out of any sub page until the bottom tabs show.
    for _ in range(6):
        nodes = dump()
        tab = find(nodes, "爱车", exact=True)
        if tab and find(nodes, "我的", exact=True):
            tap(tab.cx, tab.cy, 2)
            # The page keeps its scroll position; start from the top.
            for _ in range(2):
                drag(px(900), H - px(700), 300)
                time.sleep(0.6)
            return settle("剩余电量")
        back(1.5)
    raise RuntimeError("could not reach the car page")


def scroll_car_page_to(label):
    for _ in range(4):
        nodes = dump()
        n = find(nodes, label, exact=True)
        if n and n.y2 < H - px(350):
            return n
        drag(H - px(700), px(900))
        time.sleep(1.2)
    raise RuntimeError(f"{label} not found on the car page")


def capture_car():
    nodes = open_car_page()
    t = texts(nodes)
    car = {"raw_text": t}
    car["battery_percent"] = num(before(t, "剩余电量"))
    for i, s in enumerate(t):
        if s.startswith("更新于"):
            car["status"] = s
            if i >= 2 and t[i - 1] == "km":
                car["range_km"] = num(t[i - 2])
            if i + 1 < len(t) and not t[i + 1].startswith("修改"):
                car["name"] = t[i + 1]
        if re.search(r"(省|市).*(区|县|镇|市)", s):
            car["location"] = s
    # Lower half: climate, sentry mode.
    scroll_car_page_to("行程统计")
    t2 = texts(dump())
    car["raw_text_lower"] = t2
    for s in t2:
        if re.fullmatch(r"-?\d+(\.\d+)?°", s):
            car["cabin_temp_c"] = num(s)
    if "哨兵模式" in t2:
        i = t2.index("哨兵模式")
        if i + 1 < len(t2):
            car["sentry_mode"] = t2[i + 1]
    return car


# ------------------------------------------------------ energy & charging

def capture_energy():
    nodes = open_car_page()
    n = find(nodes, "能量管理", exact=True)
    tap(n.cx, n.cy)
    nodes = settle("充电限值")
    t = texts(nodes)
    energy = {"raw_text": t}
    for i, s in enumerate(t):
        if s.startswith("可续航里程"):
            energy["range_km"] = num(s)
            if i >= 2 and t[i - 1] == "%":
                energy["battery_percent"] = num(t[i - 2])
        if s == "充电限值":
            for s2 in t[i + 1 : i + 4]:
                if re.fullmatch(r"\d+%", s2):
                    energy["charge_limit_percent"] = num(s2)
        if s == "预约充电" and i + 1 < len(t):
            energy["scheduled_charging"] = t[i + 1]
    return energy, nodes


def parse_duration_min(s):
    h = re.search(r"(\d+)小时", s)
    m = re.search(r"(\d+)分钟", s)
    sec = re.search(r"(\d+)秒", s)
    return (int(h.group(1)) * 60 if h else 0) + (int(m.group(1)) if m else 0) + (
        round(int(sec.group(1)) / 60, 1) if sec else 0)


MONTH_ROW = re.compile(r"^(\d{2})月 充电(\d+)次 共([\d.]+)kWh$")


def capture_charging(energy_nodes, result=None, checkpoint=lambda: None):
    result = {} if result is None else result
    result["months"] = []
    n = find(energy_nodes, "充电记录", exact=True)
    tap(n.cx, n.cy)
    settle(MONTH_ROW.pattern)
    # The month in progress is left out; only complete months are recorded.
    this_month = dt.date.today().strftime("%Y-%m")
    done = {tuple(this_month.split("-"))}
    months = {}
    # Expand each month in turn; the list grows as rows open, so re-dump each time.
    for _ in range(60):
        nodes = dump()
        year = None
        target = None
        for nd in nodes:
            if re.fullmatch(r"\d{4}", nd.text):
                year = nd.text
            m = MONTH_ROW.match(nd.text)
            if m and (year, m.group(1)) not in done:
                target = (year, m.group(1), nd)
                break
        if not target:
            break
        year, mm, nd = target
        if not px(400) < nd.cy < H - px(400):
            # Bring the row to the upper third, then look again.
            delta = max(-px(1600), min(px(1600), nd.cy - H // 3))
            start = H // 2 + delta // 2
            drag(start, start - delta, 1000)
            time.sleep(1.2)
            continue
        tap(nd.cx, nd.cy, 2.0)
        done.add((year, mm))
        expanded = settle(MONTH_ROW.pattern, timeout=6)
        t = texts(expanded)
        months[(year, mm)] = parse_charging_month(t, year, mm)
        result["months"] = [value for _, value in sorted(months.items(), reverse=True)]
        checkpoint()
        log(f"charging: {year}-{mm} ({len(months[(year, mm)]['sessions'])} sessions)")
        # Fold it back so the next month's row stays put.
        again = find(expanded, f"^{mm}月 充电")
        if again and px(300) < again.cy < H - px(300):
            tap(again.cx, again.cy, 1.0)
    back()
    return result


def parse_charging_month(t, year, mm):
    header = next(i for i, s in enumerate(t) if s.startswith(f"{mm}月 充电"))
    m = MONTH_ROW.match(t[header])
    month = {"month": f"{year}-{mm}", "count": int(m.group(2)), "kwh": float(m.group(3)), "sessions": []}
    i = header + 1
    while i + 3 < len(t) and t[i] in ("交流", "直流"):
        kind, kwh, when, dur = t[i : i + 4]
        d = re.match(r"(\d+)月(\d+)日 (\d+:\d+)", when)
        start = f"{year}-{int(d.group(1)):02d}-{int(d.group(2)):02d}T{d.group(3)}" if d else when
        month["sessions"].append({
            "type": "AC" if kind == "交流" else "DC",
            "kwh": num(kwh),
            "start": start,
            "duration_min": parse_duration_min(dur),
            "duration_text": dur.replace("时长", "").strip(),
        })
        i += 4
    month["raw_text"] = t[header:i]
    if len(month["sessions"]) != month["count"]:
        log(f"warning: {year}-{mm} shows {month['count']} charges, read {len(month['sessions'])}")
    return month


# --------------------------------------------------------------- trips

def trip_page_loaded(t):
    # The page first renders 0 for everything, the odometer included.
    return num(before(t, "车辆总里程")) not in (None, 0)


def open_trip_page():
    open_car_page()
    n = scroll_car_page_to("行程统计")
    tap(n.cx, n.cy)
    return settle("车辆总里程", still=trip_page_loaded)


def picker_select(dropdown, k, top):
    """Select the k-th row of a wheel picker opened from its dropdown label.

    The wheel opens on the current selection: the trip page resets to the top
    row after a report, the assisted-driving page keeps the last pick. So roll
    k rows from the top, or one row on from the previous pick.
    """
    steps = k if dropdown.text == top else 1
    tap(dropdown.cx, dropdown.cy, 1.5)
    row = px(167)
    wheel_y = px(2290)
    for _ in range(steps):
        drag(wheel_y, wheel_y - row, 700)
        time.sleep(0.5)
    time.sleep(1.0)
    ok = find(dump(), "确定", exact=True)
    if not ok:
        raise RuntimeError("picker confirm button not found")
    tap(ok.cx, ok.cy, 2.5)


def chart_images(nodes):
    """The report's charts, in page order: distance, duration, energy, charges.

    Each chart is a canvas the tree shows as an untitled Image. Its bounds are
    real only while it is on screen; off screen they collapse to 0,0,0,0.
    """
    imgs, started = [], False
    for n, el in nodes:
        if n.text == "行驶统计":
            started = True
        if started and el.get("class", "").endswith("Image") and not n.text:
            imgs.append(n)
    return imgs


def dump_with_class():
    raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    root = ET.fromstring(raw[: raw.rfind("</hierarchy>") + len("</hierarchy>")])
    return [(Node(el), el) for el in root.iter("node")]


def show_chart(i):
    """Scroll until chart i is fully on screen near the top; return its node."""
    for _ in range(12):
        imgs = chart_images(dump_with_class())
        if i >= len(imgs):
            return None
        c = imgs[i]
        on_screen = c.y2 > c.y1
        if on_screen and c.y1 > px(250) and c.y2 < H - px(80):
            return c
        if on_screen and c.y1 > px(250):
            delta = c.y1 - px(320)  # partly below the fold: lift its top up
        elif on_screen:
            delta = -px(700)  # cut off at the top: go back up a little
        else:
            # Off screen: later charts are below the ones already on screen.
            shown = [k for k, n in enumerate(imgs) if n.y2 > n.y1]
            delta = px(1100) if not shown or max(shown) < i else -px(1100)
        start = H // 2 + delta // 2
        drag(start, start - delta, 900)
        time.sleep(1.0)
    return None


def chart_tooltip(nodes, chart, year):
    """Read the tooltip drawn inside `chart`: (date, values) or None.

    Older reports show one node like "15kWh\\n2025-8-18". Newer ones show
    "703min\\n10-6", and split each day's energy into parked (静态) and
    driving (动态) rows.
    """
    inside = [n for n in nodes if n.text and chart.x1 <= n.cx <= chart.x2 and chart.y1 <= n.cy <= chart.y2]

    def iso(d):
        p = list(map(int, d.split("-")))
        y, m, dd = p if len(p) == 3 else [year] + p
        return f"{y}-{m:02d}-{dd:02d}"

    for n in inside:
        m = re.fullmatch(r"([\d.]+)\s*(km|min|kWh)\n([\d-]+)", n.text)
        if m:
            return iso(m.group(3)), {"value": num(m.group(1))}
    for k, n in enumerate(inside):
        if re.fullmatch(r"(\d{4}-)?\d+-\d+", n.text):
            vals, rest = {}, [x.text for x in inside[k + 1 :]]
            for label, v in zip(rest[::2], rest[1::2]):
                if label == "静态电耗":
                    vals["parked_kwh"] = num(v)
                elif label == "动态电耗":
                    vals["driving_kwh"] = num(v)
            if vals:
                vals["value"] = round(sum(vals.values()), 1)
                return iso(n.text), vals
    return None


CHARTS = ["distance_km", "duration_min", "energy_kwh"]


def read_daily(year):
    """Tap every bar of the report's three charts and read its tooltip."""
    days = {}
    slots = None

    def record(key, tt):
        row = days.setdefault(tt[0], {"date": tt[0]})
        row[key] = tt[1]["value"]
        split = (("parked_kwh", "energy_parked_kwh"), ("driving_kwh", "energy_driving_kwh"))
        if any(src in tt[1] for src, _ in split):
            # The tooltip leaves out rows that are 0.
            for src, dst in split:
                row[dst] = tt[1].get(src, 0.0)

    def sweep(key, chart, y):
        # One category slot per day with data, at most 31, so 32 evenly
        # spaced taps across the chart reach every one.
        hits = {}
        for k in range(32):
            x = chart.x1 + (k + 0.5) * (chart.x2 - chart.x1) / 32
            tap(x, y, 0.4)
            tt = chart_tooltip(dump(), chart, year)
            if tt:
                hits.setdefault(tt[0], []).append(x)
                record(key, tt)
        return hits

    for i, key in enumerate(CHARTS):
        chart = show_chart(i)
        if not chart:
            log(f"warning: chart {key} not found")
            continue
        y = (chart.y1 + chart.y2) // 2
        if slots is None:
            # Sweep the first chart, then tap each day where it was hit on
            # the others, which share its days.
            hits = sweep(key, chart, y)
            slots = [(d, xs[len(xs) // 2]) for d, xs in sorted(hits.items())]
            continue
        missed = []
        for d, x in slots:
            # A tap now and then draws no tooltip; nudge sideways and retry.
            for dx in (0, -px(8), px(8)):
                tap(x + dx, y, 0.4)
                tt = chart_tooltip(dump(), chart, year)
                if tt and tt[0] == d:
                    record(key, tt)
                    break
            else:
                missed.append(d)
        if missed:
            # This chart's days may not line up with the first one's.
            log(f"{key}: {missed} not read by position, sweeping the chart")
            sweep(key, chart, y)
            still = [d for d in missed if key not in days.get(d, {})]
            if still:
                log(f"warning: {key} has no value for {still}")
    return [days[d] for d in sorted(days)]


def parse_trip_summary(t):
    s = {}
    for i, x in enumerate(t):
        if x == "总里程":
            s["distance_km"] = num(t[i - 1])
        elif x == "总电耗":
            s["energy_kwh"] = num(t[i - 1])
        elif "百公里电耗" in x:
            s["consumption_kwh_per_100km"] = num(t[i - 1])
            s["consumption_basis"] = "driving only" if "动态" in x and "静态" not in x else "driving and parked"
    return s


def parse_report(t):
    # Past months open a 用车报告; the current month opens a 行程统计 page with
    # a date range, fewer figures, and the charge count after its label.
    r = {"raw_text": t}
    r["title"] = next((x for x in t if x.endswith("用车报告")), None) or \
        next((x for x in t if re.search(r"日 - .*共\d+天$", x)), None)
    pairs = {
        "行驶里程": "distance_km", "用车时长": "duration_h", "出行次数": "trips",
        "单次最远距离": "longest_trip_km", "单次最高时长": "longest_trip_h",
        "总电耗": "energy_kwh", "动态电耗": "driving_energy_kwh",
    }
    for label, key in pairs.items():
        if label in t:
            r[key] = num(before(t, label))
    label = next((x for x in t if "百公里电耗" in x), None)
    if label:
        r["consumption_kwh_per_100km"] = num(before(t, label))
    if "充电次数" in t:
        i = t.index("充电次数")
        after = t[i + 1] if i + 1 < len(t) else ""
        r["charges"] = num(after) if re.fullmatch(r"\d+", after) else num(t[i - 1])
    if "里程超越" in t:
        r["distance_beats_percent"] = num(t[t.index("里程超越") - 2])
    joined = "|".join(t).replace("相较上月|", "相较上月")
    r["vs_last_month"] = re.findall(r"相较上月([+-]?[\d.]+%)", joined)
    return r


# ------------------------------------------------- share links via WeChat

SHARE_URL = re.compile(r"https://snc-static\.zeekrlife\.com/minapp/wap/trip/reportMonth\?key=[0-9a-f]+")
CARD_TITLE = "新的行程统计已出炉！"


def wait_for(pattern, timeout=12.0, exact=True):
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = dump()
        n = find(nodes, pattern, exact=exact)
        if n:
            return n, nodes
        time.sleep(0.6)
    raise RuntimeError(f"timed out waiting for {pattern!r}")


def share_link_for_open_report(report_nodes=None):
    """Share the open past-month report to WeChat's 文件传输助手 and return its link.

    The report's share button sends a link card through WeChat; each month's
    link is fixed, so this runs once per month and the link is kept. The card
    goes only to 文件传输助手, checked on WeChat's confirm sheet before 发送.
    Returns None when the page has no share button (the current month).
    """
    icons = [n for n in (report_nodes if report_nodes is not None else dump())
             if n.class_name.endswith("TextView") and not n.text
             and n.y2 < px(300) and n.x1 > W // 2]
    if len(icons) < 2:
        return None
    share = min(icons, key=lambda n: n.x1)
    tap(share.cx, share.cy)
    wx, _ = wait_for("微信")
    tap(wx.cx, wx.y1 - px(80))
    # Zeekr asks before launching WeChat, and a cloned WeChat adds a chooser.
    for _ in range(6):
        nodes = dump()
        if find(nodes, "选择聊天", exact=True):
            break
        agree = find(nodes, "同意", exact=True)
        if agree:
            tap(agree.cx, agree.cy)
            continue
        if find(nodes, "选择打开的应用", exact=True):
            main = find(nodes, "微信", exact=True)
            tap(main.cx, main.y1 - px(80))
            continue
        time.sleep(1.0)
    search, _ = wait_for("搜索")
    tap(search.cx, search.cy, 1.5)
    adb("shell", "input", "text", "wenjianchuanshu")
    target, _ = wait_for("文件传输助手")
    tap(target.cx, target.cy)
    send, nodes = wait_for("发送")
    if not (find(nodes, "发送给：", exact=True) and find(nodes, "文件传输助手", exact=True)
            and find(nodes, CARD_TITLE, exact=True)):
        raise RuntimeError("WeChat confirm sheet is not 文件传输助手 with the report card; not sending")
    tap(send.cx, send.cy)
    stay, _ = wait_for("留在微信")
    tap(stay.cx, stay.cy, 3.0)
    # Open the newest card, copy its link from the ⋯ menu.
    cards = [n for n in dump() if n.text == CARD_TITLE]
    card = max(cards, key=lambda n: n.y1)
    tap(card.cx, card.cy, 4.0)
    tap(W - px(92), px(222))
    copy, _ = wait_for("复制链接")
    tap(copy.cx, copy.cy, 1.5)
    back(2.0)
    # Read the clipboard by pasting into the chat box, then clear it unsent.
    box = next(n for n, el in dump_with_class() if el.get("class", "").endswith("EditText"))
    tap(box.cx, box.cy, 1.2)
    adb("shell", "input", "keyevent", "279")  # KEYCODE_PASTE
    time.sleep(1.2)
    text = next((n.text for n, el in dump_with_class() if el.get("class", "").endswith("EditText")), "")
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    adb("shell", "input", "keyevent", *["KEYCODE_DEL"] * (len(text) + 5))
    back(1.0)
    m = SHARE_URL.search(text)
    # Back to the Zeekr report, which stays open under WeChat.
    adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(2.5)
    if not m:
        raise RuntimeError(f"copied text is not a report link: {text[:80]!r}")
    return m.group(0)


def fetch_share_data(urls):
    """Month JSON for each share link, loaded by fetch_share.mjs in headless Chrome."""
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fetch_share.mjs")
    out = subprocess.run(["node", script, *urls], capture_output=True, text=True)
    if out.returncode:
        raise RuntimeError(out.stderr.strip())
    return {r["url"]: r["data"] for r in json.loads(out.stdout)}


def daily_from_share(d):
    """Daily rows from a shareQuery month, in the same shape read_daily returns."""
    year, month = d["year"], d["month"]
    dyn, sta, legacy = (d.get(key) or [] for key in ("dynamicEcY", "staticEcY", "ecy"))
    split_available = dyn and sta and (sum(dyn) + sum(sta) or not sum(legacy))
    rows = []
    for i, day in enumerate(d["dailyMileageX"]):
        row = {"date": f"{year}-{month:02d}-{int(day):02d}",
               "distance_km": d["dailyMileageY"][i], "duration_min": d["drivingTimeY"][i]}
        # Formats can change within a month: November 6, 2025 uses ecy while
        # the other days split driving and parked energy. Choose per day.
        if split_available and (dyn[i] + sta[i] or not legacy or not legacy[i]):
            row["energy_driving_kwh"], row["energy_parked_kwh"] = dyn[i], sta[i]
            row["energy_kwh"] = round(dyn[i] + sta[i], 1)
        elif legacy:
            row["energy_kwh"] = legacy[i]
        rows.append(row)
    return rows


def load_trip_cache(directory, links):
    """Reuse complete screen reports; refresh their signed share data in one batch."""
    cached = {}
    for path in sorted(glob.glob(os.path.join(directory, "zeekr-*.json")), key=os.path.getmtime):
        try:
            with open(path) as f:
                snapshot = json.load(f)
        except (OSError, ValueError):
            continue
        for entry in snapshot.get("trips", {}).get("months", []):
            month = entry.get("month")
            if not month or not entry.get("report") or not entry.get("summary"):
                continue
            if not links.get(month) or entry.get("share_url") != links[month]:
                continue
            source_month = entry.get("share_data", {}).get("yearMonth")
            title = entry["report"].get("title") or ""
            title_match = re.match(r"(\d{4})年(\d+)月", title)
            title_month = (f"{int(title_match[1])}-{int(title_match[2]):02d}"
                           if title_match else None)
            if title_month != month or (source_month is not None and source_month != month):
                continue
            cached[month] = copy.deepcopy(entry)
    return cached


def month_before(today, count):
    index = today.year * 12 + today.month - 1 - count
    year, month = divmod(index, 12)
    return f"{year}-{month + 1:02d}"


def capture_trips(trips, max_months, links_path, share, checkpoint=lambda: None,
                  refresh_reports=False):
    """Fill `trips` month by month, so a failure keeps the months already read.

    Past months come from their share links (kept in links_path, shared once
    when missing and `share` is on); months without one are read by tapping.
    """
    links = {}
    if os.path.exists(links_path):
        with open(links_path) as f:
            links = json.load(f)
    cached = {} if refresh_reports else load_trip_cache(os.path.dirname(links_path), links)

    def save_links():
        temporary = links_path + ".tmp"
        with open(temporary, "w") as f:
            json.dump(links, f, ensure_ascii=False, indent=2, sort_keys=True)
            f.write("\n")
        os.replace(temporary, links_path)

    nodes = open_trip_page()
    t = texts(nodes)
    trips.update({"odometer_km": num(before(t, "车辆总里程")), "months": []})
    today = dt.date.today()
    seen = {today.strftime("%Y-%m")}
    trips["reused_months"] = []
    # Row 0 is the month in progress; only complete months are recorded.
    for k in range(1, max_months + 1):
        expected = month_before(today, k)
        if expected in cached:
            trips["months"].append(cached[expected])
            trips["reused_months"].append(expected)
            seen.add(expected)
            log(f"trips: {expected} reused screen report")
            checkpoint()
            continue
        if nodes is None:
            nodes = settle("车辆总里程", still=trip_page_loaded)
        dd = find(nodes, r"^(本月|\d{4}年\d+月)$")
        picker_select(dd, k, "本月")
        nodes = settle("数据$")
        t = texts(nodes)
        label = next((x for x in t if re.fullmatch(r"(本月|\d{4}年\d+月)数据", x)), None)
        if label is None:
            raise RuntimeError("trip month label not found")
        month = dt.date.today().strftime("%Y-%m") if label == "本月数据" else \
            "{}-{:02d}".format(*map(int, re.findall(r"\d+", label)))
        if month in seen:
            break  # the wheel stopped rolling: past the oldest month
        seen.add(month)
        log(f"trips: {month}")
        entry = {"month": month, "summary": parse_trip_summary(t)}
        trips["months"].append(entry)
        more = find(nodes, "查看本月更多数据", exact=True)
        if more:
            tap(more.cx, more.cy)
            rep = settle("单次最远距离")
            entry["report"] = parse_report(texts(rep))
            link = links.get(month)
            if not link and share:
                link = share_link_for_open_report(rep)
                if link:
                    links[month] = link
                    save_links()
                    log(f"trips: {month} shared (link saved)")
            if link:
                entry["share_url"] = link  # filled from the link after the loop
            else:
                entry["daily"] = read_daily(int(month[:4]))
            back(0)
            nodes = None
        checkpoint()
    back()
    pending = [e for e in trips["months"] if "share_url" in e]
    if pending:
        log(f"trips: fetching {len(pending)} months from share links")
        for entry in pending:
            entry.pop("share_data", None)
            entry.pop("daily", None)
        data = fetch_share_data([e["share_url"] for e in pending])
        failed = []
        for e in pending:
            d = data.get(e["share_url"])
            if not d or d.get("yearMonth") != e["month"]:
                log(f"warning: share link for {e['month']} returned {d and d.get('yearMonth')}")
                failed.append(e["month"])
                continue
            e["share_data"] = d
            e["daily"] = daily_from_share(d)
        checkpoint()
        if failed:
            raise RuntimeError("share data missing or from the wrong month: " + ", ".join(failed))


# ------------------------------------------------------- assisted driving

def parse_assist(t):
    a = {"raw_text": t}
    s = [x.strip() for x in t]
    try:
        i = s.index("辅助驾驶行车数据")
        a["nzp_km"], a["nzp_km_percent"] = num(s[i + 1]), num(s[i + 4])
        a["acc_lcc_km"], a["acc_lcc_km_percent"] = num(s[i + 6]), num(s[i + 9])
    except (ValueError, IndexError):
        pass
    a["total_km"] = num(before(s, "总里程"))
    a["total_h"] = num(before(s, "总时长"))
    if "总里程" in s:
        j = s.index("总里程") + 1
        if j < len(s) and s[j].startswith("已超越"):
            a["beats_percent"] = num(s[j])
            j += 1
        try:
            a["nzp_h"], a["nzp_h_percent"] = num(s[j]), num(s[j + 3])
            a["acc_lcc_h"], a["acc_lcc_h_percent"] = num(s[j + 5]), num(s[j + 8])
        except IndexError:
            pass
    a["parking_assists"] = num(before(s, "智能泊车辅助次数"))
    a["parking_assist_avg_s"] = num(before(s, "智能泊车辅助平均时长"))
    if "爱车已陪伴您" in "".join(s):
        joined = "".join(s)
        a["days_owned"] = num(joined.split("爱车已陪伴您")[1])
    return a


def capture_assist():
    nodes = open_trip_page()
    card = next((n for n in nodes if n.text.startswith("drive.")), None)
    if card:
        tap(card.cx, card.cy)
    else:  # the card is an image below the month summary
        note = find(nodes, "月度行程报告")
        tap(W // 2, note.y2 + px(290))
    nodes = settle("辅助驾驶行车数据")
    periods = []
    seen = set()
    for k in range(30):
        if k:
            dd = find(dump(), r"^(自提车至今|\d{4}年\d+月)$")
            picker_select(dd, k, "自提车至今")
            nodes = settle("辅助驾驶行车数据")
        t = texts(nodes)
        i = t.index("统计周期：") if "统计周期：" in t else -1
        label = t[i + 1] if i >= 0 else None
        if label in seen:
            break
        seen.add(label)
        log(f"assist: {label}")
        p = parse_assist(t)
        p["period"] = "since_delivery" if label == "自提车至今" else \
            "{}-{:02d}".format(*map(int, re.findall(r"\d+", label)))
        periods.append(p)
    back()
    back()
    return {"periods": periods}


# ----------------------------------------------------------------- main

def main():
    global SERIAL, W, H
    os.umask(0o077)
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=".private/zeekr/captures")
    ap.add_argument("--sections", default="car,energy,charging,trips,assist")
    ap.add_argument("--months", type=int, default=25, help="most complete trip months to read, newest first")
    ap.add_argument("--serial")
    ap.add_argument("--profile", action="store_true", help="print where the time went")
    ap.add_argument("--refresh-reports", action="store_true",
                    help="reopen saved monthly reports on the phone instead of reusing them")
    ap.add_argument("--no-share", action="store_true",
                    help="don't send new report links to WeChat 文件传输助手; tap months without a saved link")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, "zeekr-" + dt.datetime.now().strftime("%Y-%m-%d-%H%M%S") + ".json")
    began = time.time()
    SERIAL = args.serial
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    if not m:
        sys.exit("no device: check `adb devices`")
    W, H = int(m.group(1)), int(m.group(2))
    adb("shell", "svc", "power", "stayon", "true")
    sections = args.sections.split(",")
    snap = {"captured_at": dt.datetime.now().isoformat(timespec="seconds"), "app": PACKAGE}

    def save_snapshot():
        temporary = path + ".tmp"
        with open(temporary, "w") as f:
            json.dump(snap, f, ensure_ascii=False, indent=2)
            f.write("\n")
        os.replace(temporary, path)

    try:
        if "car" in sections:
            log("car page")
            snap["car"] = capture_car()
            save_snapshot()
        if "energy" in sections or "charging" in sections:
            log("energy management")
            energy, nodes = capture_energy()
            if "energy" in sections:
                snap["energy"] = energy
            if "charging" in sections:
                log("charging records")
                snap["charging"] = {}
                capture_charging(nodes, snap["charging"], save_snapshot)
            save_snapshot()
            back()
        if "trips" in sections:
            snap["trips"] = {}
            capture_trips(snap["trips"], args.months,
                          os.path.join(args.out, "share-links.json"), not args.no_share,
                          save_snapshot, args.refresh_reports)
        if "assist" in sections:
            snap["assisted_driving"] = capture_assist()
            save_snapshot()
    finally:
        adb("shell", "svc", "power", "stayon", "false")
        save_snapshot()
        if args.profile:
            log(f"total {time.time() - began:.0f}s")
            for k, (n, s) in sorted(PROFILE.items(), key=lambda kv: -kv[1][1]):
                log(f"  {k:8} {n:5} calls {s:7.1f}s  avg {s / n:.2f}s")
        print(path)


if __name__ == "__main__":
    main()
