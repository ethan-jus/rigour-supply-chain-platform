import importlib.util
import json
from pathlib import Path
import unittest

root=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('prepare',Path(__file__).with_name('prepare-billiards-import.py'))
prepare=importlib.util.module_from_spec(spec);spec.loader.exec_module(prepare)
nodes={n['code']:n for n in json.loads((root/'reference-data/admin-divisions/mca-20251231.json').read_text())['nodes']}


def source(**values):
    data={'门店名称':'测试台球俱乐部','地址':'测试路1号','电话':None,'经纬度':'120.1,30.2','高德 POI ID':'B000000001','行政区划代码':'330105','高德分类':'体育休闲服务;运动场馆;台球厅'}
    data.update(values)
    return {'file':'浙江省_测试.xlsx','sheet':'名称近似全部门店','row':2,'data':data}


class BilliardsPreparationTest(unittest.TestCase):
    def test_region_and_administrative_parentage_are_from_catalog(self):
        row,errors=prepare.normalize(source(),nodes,'浙江省')
        self.assertEqual([],errors)
        self.assertEqual(('EAST','330000','330100','330105'),tuple(row[k] for k in ('geoRegionCode','provinceCode','cityCode','countyCode')))
        city,errors=prepare.normalize(source(**{'行政区划代码':'441900'}),nodes,'广东省')
        self.assertEqual([],errors);self.assertEqual('LEGAL_CITY_LEAF',city['areaResolution']);self.assertIsNone(city['countyCode'])
        beijing,_=prepare.normalize(source(**{'行政区划代码':'110101'}),nodes,'北京市')
        self.assertEqual('110000',beijing['cityCode'])

    def test_nonvenues_missing_address_unmapped_area_and_bad_coordinates_are_reviewed(self):
        for change,expected in [({'地址':None},'MISSING_OR_INVALID_ADDRESS'),({'行政区划代码':'232718'},'ADMIN_CODE_UNMATCHED'),({'经纬度':'NaN,30'},'INVALID_COORDINATES'),({'门店名称':'台球器材批发'},'POSSIBLE_EQUIPMENT_SUPPLIER'),({'门店名称':'测试台球厅(暂停营业)'},'POSSIBLY_CLOSED'),({'门店名称':'篮球场','高德分类':'体育休闲服务;运动场馆;篮球场'},'VENUE_TYPE_UNCONFIRMED')]:
            row,errors=prepare.normalize(source(**change),nodes)
            self.assertIsNone(row);self.assertIn(expected,errors)

    def test_coordinate_alone_does_not_merge_different_venues(self):
        first,_=prepare.normalize(source(),nodes)
        second,_=prepare.normalize(source(**{'门店名称':'另一家台球厅','高德 POI ID':'B000000002'}),nodes)
        result,merged=prepare.deduplicate([first,second]);self.assertEqual(2,len(result));self.assertFalse(merged)

    def test_identical_venue_preserves_multiple_poi_aliases(self):
        first,_=prepare.normalize(source(),nodes)
        second,_=prepare.normalize(source(**{'高德 POI ID':'B000000002'}),nodes)
        result,merged=prepare.deduplicate([first,second]);self.assertEqual(1,len(result));self.assertEqual(2,len(result[0]['identities']));self.assertEqual(1,len(merged))

    def test_same_name_and_address_far_apart_remain_separate(self):
        first,_=prepare.normalize(source(),nodes)
        second,_=prepare.normalize(source(**{'经纬度':'120.5,30.2','高德 POI ID':'B000000002'}),nodes)
        self.assertEqual(2,len(prepare.deduplicate([first,second])[0]))
        second['identities'][0]['poi']='B000000001'
        with self.assertRaisesRegex(ValueError,'Conflicting'):prepare.deduplicate([first,second])


if __name__=='__main__':unittest.main()
