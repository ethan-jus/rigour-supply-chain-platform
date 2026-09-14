"""不访问运行环境的部署契约测试，避免服务清单、配置和权限范围漂移。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import business_config
from service_catalog import CORE, DOMAINS, PORTS, business_compose, schemas_for, select_services


class BusinessDeployTest(unittest.TestCase):
    def test_all_modules_and_ports_are_unique(self):
        self.assertEqual(12, len(DOMAINS))
        self.assertEqual(14, len(set(PORTS.values())))
        repo = Path(__file__).resolve().parents[2]
        for row in DOMAINS:
            self.assertTrue((repo / 'services' / row[2] / 'pom.xml').is_file())

    def test_selection_is_explicit_and_dependency_ordered(self):
        self.assertEqual(CORE + ['settings','crm'], select_services('crm,settings'))
        self.assertEqual(15, len(select_services()))
        with self.assertRaises(ValueError):
            select_services('unknown')
        self.assertEqual(['rigour_iam','rigour_settings'], schemas_for(select_services('settings')))

    def test_each_container_has_own_env_and_persistent_data(self):
        for name, config in business_compose().items():
            self.assertEqual(f'/srv/rigour-dev/apps/config/{name}.env', config['env_file'])
            self.assertIn(f'/srv/rigour-dev/apps/data/{name}:/app/data', config['volumes'])
            self.assertEqual('640m',config['mem_limit'])
            self.assertNotIn('root',config['environment']['JAVA_TOOL_OPTIONS'])

    def test_properties_preserve_real_identity_and_local_databases(self):
        for row in DOMAINS:
            text = business_config.domain_properties(row)
            self.assertIn('127.0.0.1:13306/rigour_' + row[4],text)
            self.assertIn('spring.flyway.baseline-on-migrate=false',text)
            self.assertIn('spring.flyway.clean-disabled=true',text)
            self.assertIn('RIGOUR_CONTEXT_TRUST_KEY_V1',text)
            self.assertNotIn('82.157.4.176',text)
            self.assertNotIn('username=root',text)
            self.assertNotIn('out-of-order=true',text)

    def test_missing_cos_blocks_before_any_mutation(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / '.env').write_text('NACOS_PASSWORD=test-only\n')
            with patch.object(business_config,'ROOT',root), patch.object(business_config,'mysql') as sql:
                with self.assertRaisesRegex(RuntimeError,'缺少真实外部配置'):
                    business_config.prepare(select_services())
                sql.assert_not_called()
                self.assertFalse((root / 'apps').exists())

    def test_domain_env_does_not_leak_other_domain_or_root_password(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'apps/config').mkdir(parents=True)
            keys = ['CRM_DB_APP_PASSWORD','CRM_DB_MIGRATOR_PASSWORD','NACOS_PASSWORD',
                    'REDIS_PASSWORD','RIGOUR_CONTEXT_TRUST_KEY_V1','MYSQL_ROOT_PASSWORD','IAM_DB_APP_PASSWORD']
            (root / '.env').write_text(''.join(key + '=test-only\n' for key in keys))
            with patch.object(business_config,'ROOT',root):
                business_config.prepare(select_services('crm'))
            result = (root / 'apps/config/crm.env').read_text()
            self.assertIn('CRM_DB_APP_PASSWORD=',result)
            self.assertNotIn('MYSQL_ROOT_PASSWORD',result)
            self.assertNotIn('IAM_DB_APP_PASSWORD',result)


if __name__ == '__main__':
    unittest.main()
