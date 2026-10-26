"""不访问运行环境的部署契约测试，避免服务清单、配置和权限范围漂移。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import business_config
from service_catalog import (CORE, DOMAINS, PORTS, backup_schemas, business_compose,
                             retired_services, schemas_for, select_services)


class BusinessDeployTest(unittest.TestCase):
    def test_mysql_keeps_stdin_open_and_returns_query_result(self):
        with patch.object(business_config.subprocess, 'run') as command:
            command.return_value.stdout = 'rigour_iam\n'
            self.assertEqual('rigour_iam\n', business_config.mysql('SELECT schema_name FROM information_schema.schemata;'))
            args, kwargs = command.call_args
            self.assertEqual(['docker', 'exec', '-i'], args[0][:3])
            self.assertEqual('SELECT schema_name FROM information_schema.schemata;', kwargs['input'])
            self.assertTrue(kwargs['check'])

    def test_all_modules_and_ports_are_unique(self):
        self.assertEqual(9, len(DOMAINS))
        self.assertEqual(11, len(set(PORTS.values())))
        repo = Path(__file__).resolve().parents[2]
        for row in DOMAINS:
            self.assertTrue((repo / 'services' / row[2] / 'pom.xml').is_file())

    def test_selection_is_explicit_and_dependency_ordered(self):
        self.assertEqual(CORE + ['settings','crm'], select_services('crm,settings'))
        self.assertEqual(12, len(select_services()))
        with self.assertRaises(ValueError):
            select_services('unknown')
        self.assertEqual(['rigour_iam','rigour_settings'], schemas_for(select_services('settings')))

    def test_retired_services_cannot_be_selected_or_deployed(self):
        for name in ('collaboration', 'city', 'channel'):
            with self.subTest(service=name):
                with self.assertRaises(ValueError):
                    select_services(name)
                self.assertNotIn(name, business_compose())

    def test_upgrade_preserves_only_stopped_known_retired_services(self):
        selected = select_services()
        previous = selected + ['city', 'channel', 'collaboration']
        self.assertEqual(['channel','city','collaboration'], retired_services(previous, selected, ['iam']))
        with self.assertRaisesRegex(ValueError, '仍在运行'):
            retired_services(previous, selected, ['city'])
        with self.assertRaisesRegex(ValueError, '遗漏现有服务'):
            retired_services(previous, select_services('hr'), [])
        with self.assertRaisesRegex(ValueError, '遗漏现有服务'):
            retired_services(previous + ['unrecognized'], selected, [])

    def test_backup_includes_existing_retired_schemas_without_unrelated_databases(self):
        existing = {'rigour_iam','rigour_hr','rigour_city','rigour_channel',
                    'rigour_collaboration','mysql','unrelated'}
        self.assertEqual(['rigour_iam','rigour_hr','rigour_city','rigour_channel',
                          'rigour_collaboration'], backup_schemas(existing))

    def test_hr_cos_is_required_before_configuration_mutation(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / '.env').write_text('NACOS_PASSWORD=test-only\n')
            with patch.object(business_config,'ROOT',root):
                with self.assertRaisesRegex(RuntimeError,'RIGOUR_COS_SECRET_ID'):
                    business_config.prepare(select_services('hr'))
            self.assertFalse((root / 'apps').exists())

    def test_cost_writers_receive_cos_and_preserve_existing_properties(self):
        for name in ('hr', 'order'):
            with self.subTest(service=name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                config = root / 'apps/config'
                config.mkdir(parents=True)
                keys = [name.upper()+'_DB_APP_PASSWORD', name.upper()+'_DB_MIGRATOR_PASSWORD',
                        'NACOS_PASSWORD', 'REDIS_PASSWORD', 'RIGOUR_CONTEXT_TRUST_KEY_V1',
                        'MYSQL_ROOT_PASSWORD']
                (root / '.env').write_text(''.join(k + '=test-only\n' for k in keys))
                cos = {k: 'cos-test-only' for k in business_config.external_keys(name)}
                cos.update(RIGOUR_COS_SESSION_TOKEN='optional-token',
                           RIGOUR_ERP_PRODUCT_MEDIA_COS_SECRET_KEY='other-domain-secret')
                (config / 'external.env').write_text(''.join(k + '=' + v + '\n' for k,v in cos.items()))
                (config / (name+'.properties')).write_text('existing-setting=preserved\n')
                with patch.object(business_config, 'ROOT', root):
                    business_config.prepare(select_services(name))
                result = (config / (name+'.env')).read_text()
                self.assertIn('RIGOUR_COS_SECRET_ID=cos-test-only', result)
                self.assertIn('RIGOUR_COS_SESSION_TOKEN=optional-token', result)
                self.assertNotIn('other-domain-secret', result)
                self.assertNotIn('MYSQL_ROOT_PASSWORD', result)
                if name == 'order':
                    self.assertIn('RIGOUR_ORDER_FUND_ATTACHMENT_COS_ENABLED=true', result)
                else:
                    self.assertNotIn('RIGOUR_ORDER_FUND_ATTACHMENT_COS_ENABLED', result)
                self.assertEqual('existing-setting=preserved\n', (config / (name+'.properties')).read_text())
                self.assertEqual(0o600, (config / (name+'.env')).stat().st_mode & 0o777)

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
