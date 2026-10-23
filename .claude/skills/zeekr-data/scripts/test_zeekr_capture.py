"""Run with python3 -m unittest discover -s .claude/skills/zeekr-data/scripts."""

import copy
import datetime as dt
import json
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

import zeekr_capture as capture


def entry(month):
    year, number = map(int, month.split('-'))
    return {
        'month': month,
        'summary': {'distance_km': 10},
        'report': {'title': f'{year}年{number:02d}月 用车报告', 'distance_km': 10},
        'share_url': f'https://example.test/{month}',
        'share_data': {
            'year': year, 'month': number, 'yearMonth': month,
            'dailyMileageX': [1], 'dailyMileageY': [10], 'drivingTimeY': [15],
            'dynamicEcY': [2], 'staticEcY': [0.1], 'ecy': [0],
        },
    }


class TripCacheTests(unittest.TestCase):
    def test_cache_rejects_wrong_links_and_wrong_months(self):
        with tempfile.TemporaryDirectory() as directory:
            good = entry('2026-09')
            bad_link = entry('2026-08')
            bad_month = entry('2026-07')
            bad_month['share_data']['yearMonth'] = '2026-08'
            bad_title = entry('2026-06')
            bad_title['report']['title'] = '2026年07月 用车报告'
            entries = [good, bad_link, bad_month, bad_title]
            links = {e['month']: e['share_url'] for e in entries}
            links['2026-08'] = 'https://example.test/replaced'
            path = pathlib.Path(directory) / 'zeekr-2026-10-24-010000.json'
            path.write_text(json.dumps({'trips': {'months': entries}}))
            cached = capture.load_trip_cache(directory, links)
            self.assertEqual(list(cached), ['2026-09'])

    def test_interrupted_report_can_resume_before_share_fetch(self):
        with tempfile.TemporaryDirectory() as directory:
            saved = entry('2026-09')
            del saved['share_data']
            path = pathlib.Path(directory) / 'zeekr-2026-10-24-010000.json'
            path.write_text(json.dumps({'trips': {'months': [saved]}}))
            cached = capture.load_trip_cache(directory, {saved['month']: saved['share_url']})
            self.assertEqual(cached['2026-09'], saved)

    def test_cached_months_skip_phone_reports_but_refresh_daily_data(self):
        with tempfile.TemporaryDirectory() as directory:
            today = dt.date.today()
            entries = [entry(capture.month_before(today, i)) for i in (1, 2)]
            links = {e['month']: e['share_url'] for e in entries}
            links_path = pathlib.Path(directory) / 'share-links.json'
            links_path.write_text(json.dumps(links))
            snapshot = pathlib.Path(directory) / 'zeekr-previous.json'
            snapshot.write_text(json.dumps({'trips': {'months': entries}}))
            fresh = {e['share_url']: copy.deepcopy(e['share_data']) for e in entries}
            for source in fresh.values():
                source['dailyMileageY'] = [12]
            trips, checkpoints = {}, []
            with patch.object(capture, 'open_trip_page', return_value=[]), \
                    patch.object(capture, 'back'), \
                    patch.object(capture, 'picker_select') as picker, \
                    patch.object(capture, 'share_link_for_open_report') as share, \
                    patch.object(capture, 'fetch_share_data', return_value=fresh) as fetch:
                capture.capture_trips(trips, 2, str(links_path), True,
                                      lambda: checkpoints.append(copy.deepcopy(trips)))
            picker.assert_not_called()
            share.assert_not_called()
            fetch.assert_called_once_with([e['share_url'] for e in entries])
            self.assertEqual(trips['reused_months'], [e['month'] for e in entries])
            self.assertEqual([e['daily'][0]['distance_km'] for e in trips['months']], [12, 12])
            self.assertGreaterEqual(len(checkpoints), 3)

    def test_month_arithmetic_crosses_year(self):
        self.assertEqual(capture.month_before(dt.date(2026, 1, 2), 1), '2025-12')
        self.assertEqual(capture.month_before(dt.date(2026, 10, 24), 14), '2025-08')

    def test_failed_refresh_cannot_leave_stale_daily_data(self):
        with tempfile.TemporaryDirectory() as directory:
            saved = entry(capture.month_before(dt.date.today(), 1))
            saved['daily'] = [{'distance_km': 999}]
            links_path = pathlib.Path(directory) / 'share-links.json'
            links_path.write_text(json.dumps({saved['month']: saved['share_url']}))
            (pathlib.Path(directory) / 'zeekr-previous.json').write_text(
                json.dumps({'trips': {'months': [saved]}}))
            trips = {}
            with patch.object(capture, 'open_trip_page', return_value=[]), \
                    patch.object(capture, 'back'), \
                    patch.object(capture, 'fetch_share_data', return_value={
                        saved['share_url']: {'yearMonth': '1999-01'},
                    }):
                with self.assertRaisesRegex(RuntimeError, 'wrong month'):
                    capture.capture_trips(trips, 1, str(links_path), True)
            self.assertNotIn('daily', trips['months'][0])
            self.assertNotIn('share_data', trips['months'][0])

    def test_older_energy_format_preserves_ecy(self):
        source = entry('2025-08')['share_data']
        source.update(dynamicEcY=[0], staticEcY=[0], ecy=[2.4])
        daily = capture.daily_from_share(source)
        self.assertEqual(daily[0]['energy_kwh'], 2.4)
        self.assertNotIn('energy_driving_kwh', daily[0])

    def test_month_with_mixed_energy_formats_keeps_legacy_day(self):
        source = entry('2025-11')['share_data']
        source.update(dailyMileageX=['06', '07'], dailyMileageY=[181, 10],
                      drivingTimeY=[100, 15], dynamicEcY=[0, 2],
                      staticEcY=[0, 0.1], ecy=[24.8, 0])
        daily = capture.daily_from_share(source)
        self.assertEqual(daily[0]['energy_kwh'], 24.8)
        self.assertNotIn('energy_driving_kwh', daily[0])
        self.assertEqual(daily[1]['energy_driving_kwh'], 2)
        self.assertAlmostEqual(sum(row['energy_kwh'] for row in daily), 26.9)


class ChargingTests(unittest.TestCase):
    def test_duplicate_source_rows_are_preserved(self):
        row = ['直流', '22.6kWh', '04月12日 08:36', '时长9分钟']
        raw = ['04月 充电2次 共45.2kWh', *row, *row, '03月 充电0次 共0kWh']
        month = capture.parse_charging_month(raw, '2026', '04')
        self.assertEqual(len(month['sessions']), 2)
        self.assertEqual(month['sessions'][0], month['sessions'][1])
        self.assertEqual(month['raw_text'], raw[:-1])

    def test_disconnect_keeps_completed_charging_months(self):
        def node(text):
            return capture.Node(ET.Element('node', text=text, bounds='[0,500][500,700]'))

        today = dt.date.today()
        month = capture.month_before(today, 1)
        year, number = month.split('-')
        header = f'{number}月 充电1次 共10kWh'
        collapsed = [node(year), node(header)]
        expanded = [*collapsed, *map(node, ['直流', '10kWh', f'{number}月01日 08:00', '时长10分钟'])]
        result, checkpoints = {}, []
        with patch.object(capture, 'tap'), \
                patch.object(capture, 'settle', side_effect=[collapsed, expanded]), \
                patch.object(capture, 'dump', side_effect=[collapsed, RuntimeError('offline')]):
            with self.assertRaisesRegex(RuntimeError, 'offline'):
                capture.capture_charging([node('充电记录')], result,
                                         lambda: checkpoints.append(copy.deepcopy(result)))
        self.assertEqual(result['months'][0]['month'], month)
        self.assertEqual(result['months'][0]['count'], 1)
        self.assertEqual(len(checkpoints), 1)


if __name__ == '__main__':
    unittest.main()
