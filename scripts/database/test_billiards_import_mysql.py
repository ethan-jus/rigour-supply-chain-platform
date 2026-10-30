"""Real CRM V28 tables in disposable MySQL; explicit isolated schema or disposable container; never writes business tables."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import test_import_admin_divisions_mysql as admin_mysql
import uuid
from test_billiards_import import source, nodes, prepare, root

spec=importlib.util.spec_from_file_location('importer',Path(__file__).with_name('import-billiards-stores.py'))
importer=importlib.util.module_from_spec(spec);spec.loader.exec_module(importer)
spec=importlib.util.spec_from_file_location('classify',Path(__file__).with_name('backfill-billiards-classifications.py'))
classify=importlib.util.module_from_spec(spec);spec.loader.exec_module(classify)

@unittest.skipUnless(os.environ.get('BILLIARDS_MYSQL_TESTS')=='1','opt-in disposable MySQL test')
class BilliardsMySQLTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        config=os.environ.get('BILLIARDS_TEST_DB_CONFIG')
        cls.database='crm_import_test_'+uuid.uuid4().hex
        if config:
            import pymysql
            cls.driver=pymysql
            c=importer.admin_import.connect(config)
            cls.options=dict(host=c.host,port=c.port,user=c.user,password=c.password,charset='utf8mb4')
            c.close()
        else:
            admin_mysql.ImportMySQLTest.setUpClass.__func__(cls)
        c=cls.driver.connect(**cls.options,autocommit=True)
        with c.cursor() as q:
            q.execute('CREATE DATABASE '+cls.database+' CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci')
            q.execute('USE '+cls.database)
            for sql in (root/'services/rg-scdp-crm/crm-server/src/main/resources/db/migration/V28__unified_store_pool.sql').read_text().split(';'):
                if sql.strip():q.execute(sql)
        c.close()
        def drop():
            c=cls.driver.connect(**cls.options,autocommit=True)
            with c.cursor() as q:q.execute('DROP DATABASE '+cls.database)
            c.close()
        cls.addClassCleanup(drop)

    def test_import_rerun_and_batch_rollback_preserve_store_identity_and_public_state(self):
        c=self.driver.connect(**self.options,database=self.database,autocommit=False)
        self.addCleanup(c.close)
        with c.cursor() as q:
            q.execute("INSERT INTO crm_pool_policy(id,tenant_id,policy_version,status_code,created_at,updated_at,created_by,updated_by) VALUES(1,'T',1,'PUBLISHED',NOW(),NOW(),'test','test')")
        c.commit()
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)
            def data(rows):
                (directory/'accepted.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
                digest=hashlib.sha256((directory/'accepted.jsonl').read_bytes()).hexdigest()
                (directory/'manifest.json').write_text(json.dumps({'batchRef':'TEST_BATCH','catalogVersion':'MCA_20251231','acceptedSha256':digest,'sourceZipSha256':'0'*64}))
                return digest
            row,_=prepare.normalize(source(),nodes)
            row['verification']={'method':'AMAP_POI_DETAIL','checkedAt':'2026-10-29T08:00:00+00:00'}
            digest=data([row]);report=importer.apply(c,directory,'T','test',digest)
            self.assertEqual(1,report['insertedStores'])
            report=importer.apply(c,directory,'T','test',digest)
            self.assertEqual(0,report['insertedStores']);self.assertEqual(1,report['skippedStores'])
            with c.cursor() as q:
                q.execute("SELECT pool_type,cooperation_status,customer_tier,geo_region_code,city_code,owner_employee_id FROM crm_store_summary")
                self.assertEqual(('PUBLIC','NEVER','UNRATED','EAST','330100',None),q.fetchone())
                q.execute('SELECT COUNT(*) FROM crm_store_event');self.assertEqual(1,q.fetchone()[0])
                q.execute('SELECT observed_at=verified_at,observed_at FROM crm_store_identity');observed=q.fetchone();self.assertEqual(1,observed[0]);self.assertEqual('2026-10-29 08:00:00',str(observed[1]))
            catalog={'source':'test taxonomy','sourcePaths':{row['sourceCategory']:'POI_080113'}}
            classified=classify.apply(c,directory,catalog,'T','test',{'POI_080113'})
            self.assertEqual(1,classified['updatedStores'])
            self.assertEqual(0,classify.apply(c,directory,catalog,'T','test',{'POI_080113'})['updatedStores'])
            with c.cursor() as q:
                q.execute('SELECT store_type_code FROM crm_store_master');self.assertEqual('POI_080113',q.fetchone()[0])
                q.execute('SELECT COUNT(*) FROM crm_store_event');self.assertEqual(2,q.fetchone()[0])
            good,_=prepare.normalize(source(**{'高德 POI ID':'B000000002'}),nodes)
            bad,_=prepare.normalize(source(**{'高德 POI ID':'B000000003'}),nodes);bad['name']='x'*201
            digest=data([good,bad])
            with self.assertRaises(self.driver.Error):importer.apply(c,directory,'T','test',digest)
            with c.cursor() as q:
                q.execute('SELECT COUNT(*) FROM crm_store_master');self.assertEqual(1,q.fetchone()[0])
                q.execute('SELECT COUNT(*) FROM crm_store_identity');self.assertEqual(1,q.fetchone()[0])

if __name__=='__main__':unittest.main()
