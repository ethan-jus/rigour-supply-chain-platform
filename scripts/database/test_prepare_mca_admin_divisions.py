import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('prepare', Path(__file__).with_name('prepare-mca-admin-divisions.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class McaCatalogTest(unittest.TestCase):
    def setUp(self):
        self.raw = json.loads((Path(__file__).parents[2] / 'reference-data/admin-divisions/mca-20251231.raw.json').read_text())

    def test_official_snapshot_preserves_actual_levels_and_confirmed_regions(self):
        payload = prepare.convert(self.raw, '2026-10-29')
        nodes = {n['code']: n for n in payload['nodes']}
        self.assertEqual(3214, len(nodes))
        self.assertEqual('110000', nodes['110101']['parentCode'])
        self.assertEqual('COUNTY', nodes['110101']['level'])
        self.assertEqual('PROVINCE', nodes['710000']['level'])
        self.assertEqual('EAST', nodes['710000']['regionCode'])
        self.assertFalse(any(n['parentCode'] in ('441900', '710000', '810000', '820000') for n in nodes.values()))
        self.assertEqual(7, len({n['regionCode'] for n in nodes.values() if n['level'] == 'PROVINCE'}))

    def test_missing_province_rejects_partial_snapshot(self):
        self.raw['data']['children'].pop()
        with self.assertRaisesRegex(ValueError, '完整性'):
            prepare.convert(self.raw, '2026-10-29')

    def test_duplicate_or_invalid_source_code_is_rejected(self):
        self.raw['data']['children'].append(self.raw['data']['children'][0])
        with self.assertRaisesRegex(ValueError, '重复行政编码'):
            prepare.convert(self.raw, '2026-10-29')
        self.raw['data']['children'][0]['code'] = '110000123456'
        with self.assertRaisesRegex(ValueError, '六位编码'):
            prepare.convert(self.raw, '2026-10-29')

    def test_failed_response_cannot_be_imported(self):
        self.raw['status'] = 500
        with self.assertRaisesRegex(ValueError, '响应无效'):
            prepare.convert(self.raw, '2026-10-29')


if __name__ == '__main__':
    unittest.main()
