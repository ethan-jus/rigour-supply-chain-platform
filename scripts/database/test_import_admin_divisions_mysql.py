"""Opt-in integration checks against a disposable MySQL container, never a configured service DB."""
import concurrent.futures
import os
from pathlib import Path
import threading
import time
import unittest
import uuid
from test_import_admin_divisions import module, source


@unittest.skipUnless(os.environ.get("ADMIN_DIVISION_MYSQL_TESTS") == "1", "opt-in disposable MySQL test")
class ImportMySQLTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import pymysql
        from testcontainers.core.container import DockerContainer
        cls.driver = pymysql
        cls.password = uuid.uuid4().hex
        cls.container = DockerContainer("mysql:8.4").with_env("MYSQL_ROOT_PASSWORD", cls.password).with_env("MYSQL_ROOT_HOST", "%").with_exposed_ports(3306)
        cls.container.start()
        cls.addClassCleanup(cls.container.stop)
        cls.options = dict(host=cls.container.get_container_host_ip(), port=int(cls.container.get_exposed_port(3306)), user="root", password=cls.password, charset="utf8mb4", connect_timeout=3)
        for _ in range(60):
            try:
                connection = cls.driver.connect(**cls.options)
                connection.close()
                return
            except cls.driver.Error:
                time.sleep(1)
        raise RuntimeError("Disposable MySQL did not become ready")

    def setUp(self):
        self.database = "catalog_" + uuid.uuid4().hex
        connection = self.driver.connect(**self.options, autocommit=True)
        with connection.cursor() as cursor:
            cursor.execute(f"CREATE DATABASE {self.database} CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
            cursor.execute(f"USE {self.database}")
            schema = Path(__file__).resolve().parents[2] / "services/rg-scdp-foundation/foundation-server/src/main/resources/db/migration/V45__versioned_admin_divisions.sql"
            for statement in schema.read_text().split(";"):
                if statement.strip(): cursor.execute(statement)
        connection.close()
        self.connection = self.connect()
        self.addCleanup(self.connection.close)

    def connect(self):
        return self.driver.connect(**self.options, database=self.database, autocommit=False)

    def scalar(self, sql):
        self.connection.rollback()
        with self.connection.cursor() as cursor:
            cursor.execute(sql)
            return cursor.fetchone()[0]

    def test_publish_persists_parent_tree_provenance_and_rejects_overwrite(self):
        catalog = module.validate(source())
        module.persist(self.connection, catalog, "test-operator", True)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM admin_division"), 3)
        self.assertEqual(self.scalar("SELECT status_code FROM admin_division_version"), "PUBLISHED")
        self.assertEqual(self.scalar("SELECT published_by FROM admin_division_version"), "test-operator")
        with self.assertRaises(ValueError): module.persist(self.connection, catalog, "test", True)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM admin_division_version"), 1)

    def test_foreign_key_failure_rolls_back_header_and_nodes(self):
        catalog = module.validate(source())
        catalog["nodes"][-1]["parentCode"] = "999999"
        with self.assertRaises(self.driver.Error): module.persist(self.connection, catalog, "test", True)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM admin_division_version"), 0)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM admin_division"), 0)

    def test_concurrent_first_publications_have_one_winner(self):
        start = threading.Barrier(2)
        def publish(version):
            connection = self.connect()
            payload = source(); payload["version"] = version
            try:
                start.wait(timeout=10)
                module.persist(connection, module.validate(payload), "test", True)
                return True
            except (ValueError, self.driver.OperationalError):
                return False
            finally:
                connection.close()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            self.assertEqual(sum(executor.map(publish, ["TEST-A", "TEST-B"])), 1)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM admin_division_version WHERE status_code='PUBLISHED'"), 1)
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM admin_division"), 3)


if __name__ == "__main__": unittest.main()
