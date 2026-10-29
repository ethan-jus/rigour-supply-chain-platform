import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import MagicMock

spec = importlib.util.spec_from_file_location("admin_divisions", Path(__file__).with_name("import-admin-divisions.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def source():
    return {"version": "TEST-2026", "sourceName": "test fixture", "sourceRef": "test-only",
            "effectiveFrom": "2026-01-01", "nodes": [
                {"code": "330105", "name": "拱墅区", "level": "COUNTY", "parentCode": "330100"},
                {"code": "330100", "name": "杭州市", "level": "PREFECTURE", "parentCode": "330000"},
                {"code": "330000", "name": "浙江省", "level": "PROVINCE"}]}


class ImportValidationTest(unittest.TestCase):
    def test_normalizes_parent_order_and_confirmed_regions(self):
        result = module.validate(source())
        self.assertEqual([n["code"] for n in result["nodes"]], ["330000", "330100", "330105"])
        self.assertEqual(result["nodes"][0]["regionCode"], "EAST")
        self.assertIsNone(result["nodes"][1]["regionCode"])

    def test_duplicate_missing_parent_cross_province_and_cycles_fail(self):
        duplicate = source(); duplicate["nodes"].append(copy.deepcopy(duplicate["nodes"][0]))
        missing = source(); missing["nodes"] = missing["nodes"][:2]
        cross = source(); cross["nodes"][2]["code"] = "620000"; cross["nodes"][1]["parentCode"] = "620000"
        cycle = source(); cycle["nodes"][1]["parentCode"] = "330105"
        for payload in [duplicate, missing, cross, cycle]:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                module.validate(payload)

    def test_invalid_level_code_region_or_missing_provenance_fail(self):
        for field, value in [("level", "市"), ("code", 330000), ("regionCode", "SOUTH")]:
            payload = source(); payload["nodes"][2][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                module.validate(payload)
        payload = source(); payload["sourceRef"] = ""
        with self.assertRaises(ValueError): module.validate(payload)

    def test_directly_administered_county_is_supported(self):
        payload = source(); payload["nodes"][0]["parentCode"] = "330000"; payload["nodes"].pop(1)
        self.assertEqual(len(module.validate(payload)["nodes"]), 2)

    def test_existing_version_or_second_publication_rolls_back(self):
        for versions in [[("test-2026", "DRAFT")], [("OLD", "PUBLISHED")]]:
            connection = MagicMock()
            connection.cursor.return_value.__enter__.return_value.fetchall.return_value = versions
            with self.assertRaises(ValueError): module.persist(connection, module.validate(source()), "test", True)
            connection.rollback.assert_called_once()
            connection.commit.assert_not_called()

    def test_database_error_never_commits_partial_nodes(self):
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchall.return_value = []
        cursor.executemany.side_effect = RuntimeError("test failure")
        with self.assertRaises(RuntimeError): module.persist(connection, module.validate(source()), "test", True)
        connection.rollback.assert_called_once()
        connection.commit.assert_not_called()


if __name__ == "__main__": unittest.main()
