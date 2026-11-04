#!/usr/bin/env python3
"""Explicit opt-in capture of the unfinished month by tapping chart tooltips."""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re

import zeekr_capture as z


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', default='.private/zeekr/captures')
    parser.add_argument('--serial')
    args = parser.parse_args()
    os.umask(0o077)
    now = dt.datetime.now().astimezone()
    month = now.strftime('%Y-%m')
    path = Path(args.out) / f'current-{month}-{now:%Y%m%d-%H%M%S}.json'
    path.parent.mkdir(parents=True, exist_ok=True)
    snapshot = dict(captured_at=now.isoformat(timespec='seconds'), app=z.PACKAGE,
                    month=month, complete_month=False, source='screen_taps',
                    raw_screens=[], trips={})

    def save():
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + '\n')
        temporary.replace(path)

    original_dump = z.dump

    def recorded_dump():
        nodes = original_dump()
        snapshot['raw_screens'].append(dict(sequence=len(snapshot['raw_screens']) + 1,
                                            raw_text=z.texts(nodes)))
        save()
        if len(snapshot['raw_screens']) % 10 == 0:
            z.log(f"captured {len(snapshot['raw_screens'])} screen reads")
        return nodes

    z.SERIAL = args.serial
    size = re.search(r'(\d+)x(\d+)', z.adb('shell', 'wm', 'size'))
    if not size:
        raise RuntimeError('No connected phone')
    z.W, z.H = map(int, size.groups())
    z.dump = recorded_dump
    z.adb('shell', 'svc', 'power', 'stayon', 'true')
    try:
        nodes = z.dump()
        date_pattern = rf'{now.year}年{now.month:02d}月01日 - .*共\d+天$'
        if not z.find(nodes, date_pattern):
            nodes = z.open_trip_page()
            if not z.find(nodes, '本月数据', exact=True):
                raise RuntimeError('Trip page is not showing the current month')
            snapshot['trips']['summary'] = z.parse_trip_summary(z.texts(nodes))
            more = z.find(nodes, '查看本月更多数据', exact=True)
            if not more:
                raise RuntimeError('Current month detail link not found')
            z.tap(more.cx, more.cy)
            nodes = z.settle('行驶统计')
        if not z.find(nodes, date_pattern):
            raise RuntimeError('Detail page date does not match the current month')
        snapshot['trips']['report'] = z.parse_report(z.texts(nodes))
        save()
        snapshot['trips']['daily'] = z.read_daily(now.year)
        save()
        z.log('Daily charts captured; reading current month charging records')
        _, nodes = z.capture_energy()
        button = z.find(nodes, '充电记录', exact=True)
        z.tap(button.cx, button.cy)
        nodes = z.settle(z.MONTH_ROW.pattern)
        header = z.find(nodes, rf'^{now.month:02d}月 充电')
        if not header:
            raise RuntimeError('Current month charging header not found')
        z.tap(header.cx, header.cy)
        nodes = z.settle(z.MONTH_ROW.pattern)
        snapshot['charging'] = z.parse_charging_month(z.texts(nodes), str(now.year), f'{now.month:02d}')
        snapshot['capture_finished_at'] = dt.datetime.now().astimezone().isoformat(timespec='seconds')
    finally:
        save()
        z.adb('shell', 'svc', 'power', 'stayon', 'false')
        z.adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
        print(path, flush=True)


if __name__ == '__main__':
    main()
