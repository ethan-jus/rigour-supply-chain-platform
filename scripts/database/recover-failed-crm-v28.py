#!/usr/bin/env python3
"""One-time DEV recovery of the known failed V28; never repair successful Flyway history."""
import argparse
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
from datetime import datetime, timezone

NEW_TABLES = ('crm_store_master', 'crm_store_identity', 'crm_store_lead', 'crm_pool_policy',
              'crm_segment_policy', 'crm_store_ownership', 'crm_store_summary', 'crm_store_event')
FAILED_CHECKSUM = -1819233719


def preflight(cursor):
    cursor.execute('SELECT DATABASE()')
    if cursor.fetchone()[0] != 'rigour_crm':
        raise ValueError('仅允许显式指定的DEV rigour_crm库')
    cursor.execute('SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank FOR UPDATE')
    history = cursor.fetchall()
    if not history or tuple(history[-1]) != (28, '28', 'V28__unified_store_pool.sql', FAILED_CHECKSUM, 0):
        raise ValueError('迁移现场与已核实的失败V28不符，拒绝修改')
    if any(not row[4] for row in history[:-1]) or history[-2][1] != '27':
        raise ValueError('此前迁移并非全部成功，拒绝修改')
    cursor.execute('SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()')
    tables = {row[0] for row in cursor.fetchall()}
    if set(NEW_TABLES) & tables:
        raise ValueError('已有新功能表或部分执行痕迹，必须另行核查，不自动清理')
    if not {'crm_store', 'crm_customer', 'crm_party', 'crm_source_binding'} <= tables:
        raise ValueError('旧业务表不完整，拒绝修改')


def snapshot(connection, directory):
    """Consistent SQL data/schema backup, plus hashes for later comparison; no business writes."""
    manifest = {}
    with connection.cursor() as cursor, gzip.open(directory / 'crm-before.sql.gz', 'wt', encoding='utf-8') as output:
        output.write('-- CRM V28 recovery backup. Restore only into an isolated empty schema for review.\nSET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\n')
        cursor.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name")
        tables = [row[0] for row in cursor.fetchall()]
        for table in tables:
            cursor.execute(f'SHOW CREATE TABLE `{table}`')
            ddl = cursor.fetchone()[1]
            output.write(ddl + ';\n')
            cursor.execute("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=%s AND extra NOT LIKE '%%VIRTUAL GENERATED%%' AND extra NOT LIKE '%%STORED GENERATED%%' ORDER BY ordinal_position", (table,))
            columns = [row[0] for row in cursor.fetchall()]
            cursor.execute("SELECT column_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name=%s AND constraint_name='PRIMARY' ORDER BY ordinal_position", (table,))
            keys = [row[0] for row in cursor.fetchall()]
            if not keys:
                raise ValueError(f'{table}没有稳定主键，停止备份')
            names = ','.join(f'`{column}`' for column in columns)
            order = ','.join(f'`{key}`' for key in keys)
            cursor.execute(f'SELECT {names} FROM `{table}` ORDER BY {order}')
            rows = cursor.fetchall()
            digest = hashlib.sha256()
            for row in rows:
                values = ','.join('0x' + value.hex() if isinstance(value, bytes) else connection.escape(value) for value in row)
                statement = f'INSERT INTO `{table}` ({names}) VALUES ({values});\n'
                output.write(statement)
                digest.update(statement.encode('utf-8', errors='surrogateescape'))
            manifest[table] = {'rows': len(rows), 'dataSha256': digest.hexdigest(),
                               'schemaSha256': hashlib.sha256(ddl.encode()).hexdigest()}
        output.write('SET FOREIGN_KEY_CHECKS=1;\n')
        cursor.execute('SELECT * FROM flyway_schema_history WHERE installed_rank=28')
        columns = [description[0] for description in cursor.description]
        row = cursor.fetchone()
        restore = cursor.mogrify('INSERT INTO flyway_schema_history (' + ','.join(columns) + ') VALUES (' + ','.join(['%s'] * len(row)) + ');', row)
        (directory / 'restore-failed-row.sql').write_text('-- Use only before a successful V28 has been applied.\n' + restore + '\n')
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--backup-dir', required=True, type=Path)
    parser.add_argument('--apply', action='store_true', help='只删除已备份、逐项匹配的那一条失败历史；不执行DDL或业务更新')
    args = parser.parse_args()
    os.umask(0o077)
    module_path = Path(__file__).with_name('import-admin-divisions.py')
    spec = importlib.util.spec_from_file_location('admin_catalog', module_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    connection = module.connect(args.config)
    try:
        # This recovery was reviewed only for the shared development host.
        if connection.host != '192.168.12.7' or connection.port != 13306:
            raise ValueError('目标不是已核实的共享DEV，拒绝执行')
        connection.begin()
        with connection.cursor() as cursor:
            preflight(cursor)
        args.backup_dir.mkdir(parents=True, exist_ok=False)
        manifest = snapshot(connection, args.backup_dir)
        if args.apply:
            with connection.cursor() as cursor:
                preflight(cursor)
                cursor.execute("DELETE FROM flyway_schema_history WHERE installed_rank=28 AND version='28' AND script='V28__unified_store_pool.sql' AND checksum=%s AND success=0", (FAILED_CHECKSUM,))
                if cursor.rowcount != 1:
                    raise ValueError('失败记录发生变化，回滚')
            connection.commit()
        else:
            connection.rollback()
        result = {'mode': 'FAILED_ROW_REMOVED' if args.apply else 'PREVIEW_BACKED_UP',
                  'at': datetime.now(timezone.utc).isoformat(), 'backup': str(args.backup_dir),
                  'businessTables': len(manifest) - 1, 'customerRows': manifest['crm_customer']['rows'],
                  'legacyStoreRows': manifest['crm_store']['rows'], 'businessWrites': 0}
        (args.backup_dir / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
    except Exception as error:
        connection.rollback()
        if isinstance(error, ValueError):
            parser.exit(2, f'恢复已停止：{error}\n')
        parser.exit(2, f'恢复已停止（{type(error).__name__}），数据库事务已回滚；不输出连接凭据。\n')
    finally:
        connection.close()


if __name__ == '__main__':
    main()
