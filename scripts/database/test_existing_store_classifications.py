"""Evidence boundaries for filling classifications of already-imported stores."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('existing_classifications', Path(__file__).with_name('recheck-existing-store-classifications.py'))
recheck = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recheck)
catalog = json.loads((Path(__file__).resolve().parents[2] / 'reference-data/store-classifications/amap-initial-classifications.json').read_text())


class ExistingClassificationTest(unittest.TestCase):
    def setUp(self):
        self.store = {'storeId': 1, 'revision': 0, 'poi': 'B000000001', 'name': '测试台球俱乐部',
                      'address': '测试路1号', 'longitude': 120.15, 'latitude': 30.25, 'adminAreaCode': '330106'}
        self.response = {'checkedAt': '2026-10-29T10:00:00Z', 'response': {'status': '1', 'pois': [{
            'id': 'B000000001', 'name': '测试台球俱乐部', 'location': '120.15,30.25', 'adcode': '330106',
            'type': '体育休闲服务;运动场馆;台球厅', 'typecode': '080113', 'address': '测试路1号'}]}}

    def result(self, **fields):
        response = copy.deepcopy(self.response)
        response['response']['pois'][0].update(fields)
        return recheck.decide(self.store, response, catalog)

    def test_exact_identity_and_published_category_can_fill(self):
        result = self.result()
        self.assertEqual(('READY', 'POI_080113'), (result['decision'], result['storeTypeCode']))
        self.assertEqual(0, result['verification']['positionDifferenceMetres'])

    def test_missing_or_different_poi_is_not_inferred_from_name(self):
        for response in [None, {'response': {'status': '0'}}, {'response': {'status': '1', 'pois': []}}]:
            self.assertEqual('PENDING', recheck.decide(self.store, response, catalog)['decision'])
        self.assertEqual('POI_NOT_FOUND', self.result(id='B000000002')['reason'])

    def test_name_area_or_position_change_requires_review(self):
        for changes, reason in [({'name': '新测试台球俱乐部'}, 'POI_NAME_CHANGED'),
                                ({'adcode': '330105'}, 'ADMIN_CODE_CHANGED'),
                                ({'location': '121.15,30.25'}, 'POI_LOCATION_CHANGED')]:
            with self.subTest(changes=changes):
                self.assertEqual(reason, self.result(**changes)['reason'])

    def test_absent_invalid_and_non_finite_coordinates_require_review(self):
        for location in [None, [], '', 'bad', '120,30,40', 'NaN,30', '120,Infinity', '181,30', '120,91']:
            with self.subTest(location=location):
                self.assertEqual('PENDING', self.result(location=location)['decision'])

    def test_missing_unknown_and_conflicting_classifications_require_review(self):
        for changes, reason in [({'type': []}, 'CATEGORY_MISSING'),
                                ({'type': '未知;未知;未知'}, 'UNMAPPED_CATEGORY'),
                                ({'typecode': '080101'}, 'CATEGORY_CODE_CONFLICT')]:
            with self.subTest(changes=changes):
                self.assertEqual(reason, self.result(**changes)['reason'])

    def test_known_broad_category_stays_broad_and_does_not_become_billiards(self):
        result = self.result(type='体育休闲服务;体育休闲服务场所;体育休闲服务场所', typecode='080000')
        self.assertEqual(('READY', 'POI_080000'), (result['decision'], result['storeTypeCode']))

    def test_multiple_categories_prefer_explicit_billiards_only_when_both_codes_match(self):
        result = self.result(type='体育休闲服务;娱乐场所;棋牌室|体育休闲服务;运动场馆;台球厅', typecode='080306|080113')
        self.assertEqual(('READY', 'POI_080113'), (result['decision'], result['storeTypeCode']))

    def test_conflicting_sport_is_not_silently_applied(self):
        result = self.result(type='体育休闲服务;运动场馆;保龄球馆', typecode='080102')
        self.assertEqual(('PENDING', 'VENUE_CATEGORY_CONFLICT'), (result['decision'], result['reason']))

    def test_explicit_billiards_category_survives_unmapped_secondary_business(self):
        result = self.result(type='购物服务;家居建材市场;厨卫市场|体育休闲服务;运动场馆;台球厅', typecode='060604|080113')
        self.assertEqual(('READY', 'POI_080113'), (result['decision'], result['storeTypeCode']))
        swapped = self.result(type='体育休闲服务;娱乐场所;棋牌室|体育休闲服务;运动场馆;台球厅', typecode='080113|080306')
        self.assertEqual('CATEGORY_CODE_CONFLICT', swapped['reason'])


if __name__ == '__main__':
    unittest.main()
