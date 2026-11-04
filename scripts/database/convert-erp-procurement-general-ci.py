#!/usr/bin/env python3
"""Reviewed DEV maintenance for three ERP tables; never changes Flyway history.

Run without --apply first. Stop all ERP writers before --apply. The backup uses
the consistent SQL snapshot pattern from recover-failed-crm-v28.py, without its
CRM recovery logic. Backups contain business data: keep --output outside Git.
"""
import argparse
import gzip
import hashlib
import json
import os
import re
from pathlib import Path
from urllib.parse import urlparse

import pymysql

TARGETS = ("erp_supplier_profile", "erp_procurement_order", "erp_procurement_order_line")
COLLATION = "utf8mb4_general_ci"


def ident(value):
    return "`" + value.replace("`", "``") + "`"


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + "\n")


def connect(args):
    config = args.config.read_text()

    def option(name):
        match = re.search(r"^    " + name + r":\s*(.*?)\s*$", config, re.M)
        if not match:
            raise ValueError("Missing datasource option: " + name)
        return match.group(1).strip("\"'")

    url = urlparse(option("url")[5:])
    actual = (url.hostname, url.port or 3306, url.path[1:])
    if actual != (args.host, args.port, "rigour_erp"):
        raise ValueError("Datasource does not match explicitly reviewed DEV ERP target")
    return pymysql.connect(host=actual[0], port=actual[1], database=actual[2],
                           user=option("username"), password=option("password"),
                           charset="utf8mb4", connect_timeout=10, read_timeout=60,
                           write_timeout=60, autocommit=False)


def query(connection, sql, params=()):
    with connection.cursor() as cursor:
        cursor.execute(sql, params)
        return cursor.fetchall()


def inventory(connection):
    table_rows = query(connection, "SELECT table_name,engine,table_collation FROM information_schema.tables "
                       "WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name")
    tables = [row[0] for row in table_rows]
    if not set(TARGETS) <= set(tables) or any(row[1] != "InnoDB" for row in table_rows):
        raise ValueError("Missing target or non-InnoDB table; snapshot needs separate review")
    for kind, sql in (
        ("views", "SELECT COUNT(*) FROM information_schema.views WHERE table_schema=DATABASE()"),
        ("triggers", "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()"),
        ("routines", "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE()"),
        ("events", "SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE()"),
    ):
        if query(connection, sql)[0][0]:
            raise ValueError(kind + " need a separately reviewed backup")
    schemas = {table: query(connection, "SHOW CREATE TABLE " + ident(table))[0][1] for table in tables}
    columns = query(connection, "SELECT table_name,column_name,column_type,is_nullable,column_default,extra,"
                    "character_set_name,collation_name FROM information_schema.columns "
                    "WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position")
    indexes = query(connection, "SELECT table_name,index_name,non_unique,seq_in_index,column_name,sub_part,"
                    "index_type,expression FROM information_schema.statistics "
                    "WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index")
    foreign_keys = query(connection, "SELECT k.table_name,k.constraint_name,k.column_name,"
                         "k.referenced_table_name,k.referenced_column_name,c.column_type,"
                         "c.character_set_name,r.column_type,r.character_set_name "
                         "FROM information_schema.key_column_usage k "
                         "JOIN information_schema.columns c ON c.table_schema=k.table_schema "
                         "AND c.table_name=k.table_name AND c.column_name=k.column_name "
                         "JOIN information_schema.columns r ON r.table_schema=k.referenced_table_schema "
                         "AND r.table_name=k.referenced_table_name AND r.column_name=k.referenced_column_name "
                         "WHERE k.table_schema=DATABASE() AND k.referenced_table_name IS NOT NULL "
                         "ORDER BY k.table_name,k.constraint_name,k.ordinal_position")
    for fk in foreign_keys:
        if (fk[0] in TARGETS or fk[3] in TARGETS) and (fk[6] or fk[8]):
            raise ValueError("Character foreign key needs separate dependency review")
    return {"tables": table_rows, "schemas": schemas, "columns": columns,
            "indexes": indexes, "foreignKeys": foreign_keys}


def collisions(connection, info):
    column_map = {(row[0], row[1]): row for row in info["columns"]}
    groups = {}
    for index in info["indexes"]:
        table, name, non_unique, _, column, prefix, _, expression = index
        if table not in TARGETS or non_unique:
            continue
        if not column or expression:
            raise ValueError("Functional unique index needs separate review")
        value = ident(column)
        if prefix:
            value = "LEFT(" + value + "," + str(prefix) + ")"
        if column_map[(table, column)][6]:
            value = "CONVERT(" + value + " USING utf8mb4) COLLATE " + COLLATION
        groups.setdefault((table, name), []).append((column, value))
    checks = []
    for (table, name), values in groups.items():
        sql = ("SELECT COUNT(*) FROM (SELECT 1 FROM " + ident(table) + " WHERE "
               + " AND ".join(ident(column) + " IS NOT NULL" for column, _ in values)
               + " GROUP BY " + ",".join(value for _, value in values)
               + " HAVING COUNT(*)>1) conflicts")
        count = query(connection, sql)[0][0]
        checks.append({"table": table, "index": name, "conflicts": count, "sql": sql})
        if count:
            raise ValueError("Unique index conflicts after conversion: " + table + "." + name)
    return checks


def snapshot(connection, info, directory):
    """All ERP tables, stable primary-key order, executable SQL plus per-table hashes."""
    manifest = {}
    destination = directory / "erp-before.sql.gz"
    with gzip.open(destination, "wt", encoding="utf-8") as output:
        output.write("-- Restore only into an isolated EMPTY schema; never pipe into DEV.\n"
                     "SET NAMES utf8mb4;\nSET time_zone='+00:00';\n"
                     "SET FOREIGN_KEY_CHECKS=0;\n")
        sql_mode = query(connection, "SELECT @@session.sql_mode")[0][0]
        output.write("SET sql_mode=" + connection.escape(sql_mode) + ";\n")
        # FK checks are disabled in this isolated-restore file only, never during conversion.
        for table, ddl in info["schemas"].items():
            output.write(ddl + ";\n")
            columns = [row[1] for row in info["columns"] if row[0] == table
                       and "VIRTUAL GENERATED" not in row[5] and "STORED GENERATED" not in row[5]]
            keys = [row[4] for row in info["indexes"] if row[0] == table and row[1] == "PRIMARY"]
            if not keys:
                raise ValueError("No stable primary key for " + table)
            names = ",".join(map(ident, columns))
            rows = query(connection, "SELECT " + names + " FROM " + ident(table)
                         + " ORDER BY " + ",".join(map(ident, keys)))
            digest = hashlib.sha256()
            for row in rows:
                values = ",".join("0x" + value.hex() if isinstance(value, bytes)
                                  else connection.escape(value) for value in row)
                statement = "INSERT INTO " + ident(table) + " (" + names + ") VALUES (" + values + ");\n"
                output.write(statement)
                digest.update(statement.encode("utf-8", errors="surrogateescape"))
            manifest[table] = {"rows": len(rows), "dataSha256": digest.hexdigest(),
                               "schemaSha256": hashlib.sha256(ddl.encode()).hexdigest()}
        output.write("SET FOREIGN_KEY_CHECKS=1;\n")
    # Verify the gzip stream and statement counts before any DDL is allowed.
    with gzip.open(destination, "rt", encoding="utf-8") as source:
        statements = sum(line.startswith("INSERT INTO ") for line in source)
    if statements != sum(table["rows"] for table in manifest.values()):
        raise ValueError("Backup statement count mismatch")
    write_json(directory / "before-manifest.json", manifest)
    return manifest


def invariant_metadata(info):
    # The only allowed metadata changes are collation and charset on target columns/tables.
    columns = [list(row[:6]) + (list(row[6:]) if row[0] not in TARGETS else [])
               for row in info["columns"]]
    tables = [list(row[:2]) + (list(row[2:]) if row[0] not in TARGETS else [])
              for row in info["tables"]]
    other_schemas = {table: ddl for table, ddl in info["schemas"].items() if table not in TARGETS}
    return columns, tables, info["indexes"], info["foreignKeys"], other_schemas


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--review", type=Path, help="Required for apply: previously reviewed review.json")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--writes-stopped", action="store_true", help="Operator has stopped every ERP writer")
    args = parser.parse_args()
    os.umask(0o077)
    project = Path(__file__).resolve().parents[2]
    if args.output.resolve().is_relative_to(project):
        raise ValueError("Backups must be outside the Git project")
    args.output.mkdir(parents=True, exist_ok=False)
    connection = connect(args)
    try:
        query(connection, "SET SESSION time_zone='+00:00'")
        query(connection, "SET SESSION lock_wait_timeout=10")
        query(connection, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        query(connection, "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
        info = inventory(connection)
        checks = collisions(connection, info)
        server = query(connection, "SELECT DATABASE(),@@server_uuid")[0]
        plan = {"database": server[0], "serverUuid": server[1], "targets": TARGETS,
                "schemaHashes": {table: hashlib.sha256(ddl.encode()).hexdigest()
                                 for table, ddl in info["schemas"].items()}, "checks": checks}
        write_json(args.output / "review.json", plan)
        write_json(args.output / "before-inventory.json", info)
        ddl = ["ALTER TABLE " + ident(table) + " CONVERT TO CHARACTER SET utf8mb4 COLLATE "
               + COLLATION for table in TARGETS]
        (args.output / "reviewed-conversion.sql").write_text(";\n".join(ddl) + ";\n")
        if not args.apply:
            print(json.dumps({"mode": "review", "targetCount": len(TARGETS),
                              "uniqueIndexesChecked": len(checks), "conflicts": 0,
                              "output": str(args.output)}))
            return
        if not args.writes_stopped or not args.review:
            raise ValueError("Apply requires stopped writers and a reviewed plan")
        reviewed = json.loads(args.review.read_text())
        if any(reviewed[key] != json.loads(json.dumps(plan))[key]
               for key in ("database", "serverUuid", "targets", "schemaHashes")):
            raise ValueError("Reviewed schema/target changed; review again")
        # An operator may keep an idle IDE database browser open. Do not kill it;
        # reject active ERP queries/transactions in addition to the stopped-writer handoff.
        sessions = query(connection, "SELECT p.ID FROM information_schema.processlist p "
                         "LEFT JOIN information_schema.innodb_trx t ON t.trx_mysql_thread_id=p.ID "
                         "WHERE p.DB=DATABASE() AND p.ID<>CONNECTION_ID() "
                         "AND (p.COMMAND<>'Sleep' OR t.trx_mysql_thread_id IS NOT NULL)")
        if sessions:
            raise ValueError("ERP database still has active queries/transactions; stop all ERP writers")
        before = snapshot(connection, info, args.output)
        connection.rollback()
        connection.autocommit(True)
        for statement in ddl:
            query(connection, statement)
        after_info = inventory(connection)
        if invariant_metadata(info) != invariant_metadata(after_info):
            raise ValueError("Unexpected type/index/FK or unrelated table change; do not restart ERP")
        for row in after_info["columns"]:
            if row[0] in TARGETS and row[6] and (row[6], row[7]) != ("utf8mb4", COLLATION):
                raise ValueError("A target column was not converted")
        if any(row[2] != COLLATION for row in after_info["tables"] if row[0] in TARGETS):
            raise ValueError("A target table default was not converted")
        collisions(connection, after_info)
        # Reuse the exact serialization to compare all ERP rows and untouched schemas.
        after_dir = args.output / "after"
        after_dir.mkdir()
        after = snapshot(connection, after_info, after_dir)
        if any((before[table]["rows"], before[table]["dataSha256"])
               != (after[table]["rows"], after[table]["dataSha256"]) for table in before):
            raise ValueError("ERP data changed; do not restart before investigation")
        write_json(args.output / "after-inventory.json", after_info)
        result = {"mode": "converted", "targetRows": {table: before[table]["rows"] for table in TARGETS},
                  "allTableDataUnchanged": len(before), "uniqueConflicts": 0,
                  "typesIndexesForeignKeysUnchanged": True, "output": str(args.output)}
        write_json(args.output / "result.json", result)
        print(json.dumps(result))
    finally:
        connection.rollback()
        connection.close()


if __name__ == "__main__":
    main()
