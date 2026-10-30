"""Run against a disposable schema; verify classification updates, audit and reruns."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
import test_billiards_import_mysql as mysql
from test_existing_store_classifications import recheck


@unittest.skipUnless(os.environ.get('BILLIARDS_MYSQL_TESTS') == '1', 'opt-in disposable MySQL test')
class ExistingClassificationMySQLTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        mysql.BilliardsMySQLTest.setUpClass.__func__(cls)

    def test_guarded_updates_pending_audit_rerun_and_rollback(self):
        connection = self.driver.connect(**self.options, database=self.database, autocommit=False)
        self.addCleanup(connection.close)
        with connection.cursor() as q:
            q.execute("INSERT INTO crm_pool_policy(id,tenant_id,policy_version,status_code,created_at,updated_at,created_by,updated_by) VALUES(1,'T',1,'PUBLISHED',NOW(),NOW(),'test','test')")
        connection.commit()
        rows = [mysql.prepare.normalize(mysql.source(**{'高德 POI ID': 'BTEST%05d' % i, '门店名称': '测试%d台球厅' % i}), mysql.nodes)[0] for i in range(7)]
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            content = ''.join(json.dumps(row) + '\n' for row in rows)
            digest = hashlib.sha256(content.encode()).hexdigest()
            (directory / 'accepted.jsonl').write_text(content)
            (directory / 'manifest.json').write_text(json.dumps({'batchRef': 'CLASSIFICATION_TEST', 'catalogVersion': 'MCA_20251231', 'acceptedSha256': digest, 'sourceZipSha256': '0' * 64}))
            mysql.importer.apply(connection, directory, 'T', 'test', digest)
        with connection.cursor() as q:
            q.execute("SELECT CAST(i.external_id AS CHAR),m.id,m.revision FROM crm_store_master m JOIN crm_store_identity i ON m.id=i.store_id ORDER BY i.external_id")
            stores = q.fetchall()
            q.execute('UPDATE crm_store_master SET revision=revision+1 WHERE id=%s', (stores[2][1],))
            q.execute("UPDATE crm_store_master SET store_type_code='POI_080306' WHERE id=%s", (stores[3][1],))
            q.execute('DELETE FROM crm_store_identity WHERE store_id=%s', (stores[4][1],))
        connection.commit()
        decisions = [{'storeId': sid, 'poi': poi, 'revision': revision, 'decision': 'READY',
                      'storeTypeCode': 'POI_080113', 'reason': 'EXACT_POI_CLASSIFICATION', 'verification': {}}
                     for poi, sid, revision in stores]
        decisions[1].update(decision='PENDING', storeTypeCode=None, reason='POI_NAME_CHANGED')
        result = recheck.apply(connection, decisions[:5], 'T', 'test', {'POI_080113'}, 'TEST')
        self.assertEqual({'updatedStores': 1, 'pendingReviewRecorded': 1, 'concurrentChangeSkipped': 1,
                          'existingClassificationPreserved': 1, 'identityChangeSkipped': 1}, result)
        rerun = recheck.apply(connection, decisions[:5], 'T', 'test', {'POI_080113'}, 'TEST')
        self.assertEqual(2, rerun['existingClassificationPreserved'])
        self.assertEqual(1, rerun['alreadyRecorded'])
        with connection.cursor() as q:
            q.execute("SELECT event_type,COUNT(*) FROM crm_store_event WHERE request_id LIKE 'TEST:%' GROUP BY event_type")
            self.assertEqual({'SOURCE_CLASSIFICATION_APPLIED': 1, 'SOURCE_CLASSIFICATION_REVIEW_REQUIRED': 1}, dict(q.fetchall()))
            q.execute('SELECT store_type_code,revision FROM crm_store_master WHERE id=%s', (stores[0][1],))
            self.assertEqual(('POI_080113', stores[0][2] + 1), q.fetchone())
            q.execute('SELECT store_type_code FROM crm_store_master WHERE id=%s', (stores[1][1],))
            self.assertIsNone(q.fetchone()[0])
            q.execute("SELECT COUNT(*) FROM crm_store_summary WHERE pool_type='PUBLIC' AND cooperation_status='NEVER'")
            self.assertEqual(7, q.fetchone()[0])
        decisions[6]['storeTypeCode'] = 'UNPUBLISHED'
        with self.assertRaisesRegex(ValueError, 'not published'):
            recheck.apply(connection, decisions[5:], 'T', 'test', {'POI_080113'}, 'ROLLBACK')
        with connection.cursor() as q:
            q.execute('SELECT store_type_code FROM crm_store_master WHERE id=%s', (stores[5][1],))
            self.assertIsNone(q.fetchone()[0])
            q.execute("SELECT COUNT(*) FROM crm_store_event WHERE request_id LIKE 'ROLLBACK:%'")
            self.assertEqual(0, q.fetchone()[0])


if __name__ == '__main__':
    unittest.main()
