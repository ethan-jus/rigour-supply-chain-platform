import importlib.util
from pathlib import Path
import unittest
from test_billiards_import import source, nodes, prepare


def load(name,file):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

review=load('review','recheck-billiards-import.py')
classify=load('classify','backfill-billiards-classifications.py')

class RecheckTest(unittest.TestCase):
    def test_mixed_hall_is_not_mistaken_for_equipment_supplier(self):
        row=source(**{'门店名称':'傲杆台球俱乐部·器材店'})
        self.assertEqual(('CANDIDATE','MIXED_HALL_AND_EQUIPMENT'),review.decide(row))
        self.assertEqual(('EXCLUDED','EQUIPMENT_ONLY'),review.decide(source(**{'门店名称':'台球器材批发商行','高德分类':'购物服务;体育用品店;体育用品店'})))

    def test_category_conflict_or_ambiguous_supplier_is_not_admitted(self):
        self.assertEqual('PENDING',review.decide(source(**{'门店名称':'台球俱乐部','高德分类':'商务住宅;住宅区;住宅小区'}))[0])
        self.assertEqual('PENDING',review.decide(source(**{'门店名称':'姜大师台球杆专业维修工作室'}))[0])

    def test_closed_or_concordant_non_venue_is_excluded(self):
        self.assertEqual('EXCLUDED',review.decide(source(**{'门店名称':'台球俱乐部(暂停营业)'}))[0])
        self.assertEqual('EXCLUDED',review.decide(source(**{'门店名称':'冰魂篮球体适能','高德分类':'科教文化服务;培训机构;培训机构'}))[0])
        self.assertEqual('PENDING',review.decide(source(**{'门店名称':'漫8俱乐部','高德分类':'体育休闲服务;运动场馆;健身中心'}))[0])

    def test_venue_evidence_does_not_bypass_address_area_or_closed_checks(self):
        row,issues=prepare.normalize(source(**{'地址':None,'行政区划代码':'232718','门店名称':'台球俱乐部(暂停营业)'}),nodes,venue_verified=True)
        self.assertIsNone(row)
        self.assertIn('MISSING_OR_INVALID_ADDRESS',issues)
        self.assertIn('ADMIN_CODE_UNMATCHED',issues)
        self.assertIn('POSSIBLY_CLOSED',issues)

    def test_exact_poi_enrichment_preserves_blanks_and_records_field_changes(self):
        record=source(**{'地址':None})
        d=record['data'];poi={'id':d['高德 POI ID'],'name':d['门店名称'],'address':[], 'location':d['经纬度'],'adcode':d['行政区划代码'],'tel':'010-12345678','type':d['高德分类']}
        result={'checkedAt':'2026-10-29T08:00:00+00:00','response':{'status':'1','pois':[poi]}}
        enriched,evidence,blocked=review.enrich(record,result)
        self.assertIsNone(blocked);self.assertIsNone(enriched['data']['地址'])
        self.assertEqual('010-12345678',enriched['data']['电话'])
        self.assertIn('电话',evidence['changes'])
        self.assertIsNone(prepare.normalize(enriched,nodes,venue_verified=True)[0])
        poi['address']='核实后的门店地址'
        self.assertEqual('核实后的门店地址',review.enrich(record,result)[0]['data']['地址'])

    def test_poi_identity_and_movement_are_not_silently_replaced(self):
        record=source();d=record['data']
        poi={'id':'B0000OTHER','name':d['门店名称'],'location':d['经纬度']}
        result={'response':{'status':'1','pois':[poi]}}
        self.assertEqual('POI_NOT_FOUND',review.enrich(record,result)[2])
        poi['id']=d['高德 POI ID'];poi['location']='121,31'
        self.assertEqual('POI_LOCATION_CHANGED',review.enrich(record,result)[2])
        self.assertEqual('API_NOT_VERIFIED',review.enrich(record,None)[2])

    def test_classification_uses_exact_source_path_and_never_invents_unknown(self):
        mapping={'体育;娱乐;棋牌室':'POI_080502','体育;运动;台球厅':'POI_080113'}
        self.assertEqual('POI_080113',classify.classification('体育;娱乐;棋牌室|体育;运动;台球厅',mapping))
        self.assertIsNone(classify.classification('',mapping))
        with self.assertRaises(ValueError):classify.classification('未见分类',mapping)

if __name__=='__main__':unittest.main()
