#!/usr/bin/env python3
"""把飞书销售与门店 CSV 转成可直接送入 MySQL 的幂等 SQL。

脚本只向 stdout 输出 SQL，统计与拒绝原因写 stderr；原始 CSV 和生成 SQL 均不得提交 Git。
示例：

    ./import-temporary-checkin-csv.py \
      --tenant-id 00000000-0000-0000-0000-000000000000 \
      --salespersons-csv /secure/salespersons.csv \
      --stores-csv /secure/stores.csv | mysql rigour_sales_work
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo


CITY_ALLOWLIST = {
    "北京", "深圳", "杭州", "成都", "武汉", "西安", "长沙", "南京", "石家庄",
    "重庆", "苏州", "金华", "东莞", "上海", "洛阳", "广州", "总部",
}
STORE_HEADERS = {
    "城市", "属性", "名称", "营业状态", "联系人", "联系方式", "面积", "设施数",
    "经营类型", "意向业务", "合作意向", "门店等级", "门店标签", "创建时间", "地理位置",
}
SALESPERSON_HEADERS = {"销售姓名", "职位", "城市", "在职状态"}
IDENTITY_NAMESPACE = uuid.UUID("746ce285-9486-4a73-8701-018a44f40ca7")
SHANGHAI = ZoneInfo("Asia/Shanghai")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成临时销售打卡初始数据 SQL")
    parser.add_argument("--tenant-id", required=True, type=uuid.UUID)
    parser.add_argument("--salespersons-csv", required=True, type=Path)
    parser.add_argument("--stores-csv", required=True, type=Path)
    parser.add_argument("--batch-size", type=int, default=200)
    return parser.parse_args()


def read_csv(path: Path, required_headers: set[str]) -> list[dict[str, str]]:
    if not path.is_file():
        raise ValueError(f"CSV不存在: {path}")
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        missing = required_headers.difference(reader.fieldnames or [])
        if missing:
            raise ValueError(f"{path.name} 缺少字段: {','.join(sorted(missing))}")
        return [{key: (value or "").strip() for key, value in row.items()} for row in reader]


def sql_text(value: str | None) -> str:
    if value is None or value == "":
        return "NULL"
    return f"CONVERT(0x{value.encode('utf-8').hex()} USING utf8mb4)"


def sql_required_text(value: str) -> str:
    if not value:
        raise ValueError("必填文本为空")
    return sql_text(value)


def sql_uuid(value: uuid.UUID | None) -> str:
    return "NULL" if value is None else f"UNHEX('{value.hex}')"


def sql_datetime(value: datetime) -> str:
    utc_value = value.astimezone(timezone.utc).replace(tzinfo=None)
    return f"TIMESTAMP('{utc_value:%Y-%m-%d %H:%M:%S.%f}')"


def stable_uuid(tenant_id: uuid.UUID, kind: str, source_id: str) -> uuid.UUID:
    return uuid.uuid5(IDENTITY_NAMESPACE, f"{tenant_id}:{kind}:{source_id}")


def source_id(prefix: str, value: str, occurrence: int = 1) -> str:
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
    return f"{prefix}:{digest[:56]}:{occurrence}"


def parse_feishu_time(value: str, fallback: datetime) -> datetime:
    if not value:
        return fallback
    for pattern in ("%Y/%m/%d %H:%M", "%Y/%m/%d %H:%M:%S", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(value, pattern).replace(tzinfo=SHANGHAI)
        except ValueError:
            continue
    raise ValueError(f"无法解析飞书创建时间: {value}")


def split_multi(value: str) -> list[str]:
    result: list[str] = []
    for item in re.split(r"[,，]", value):
        normalized = item.strip()
        if normalized and normalized not in result:
            result.append(normalized)
    return result


def canonical_row(row: dict[str, str], headers: set[str]) -> str:
    return json.dumps({key: row.get(key, "") for key in sorted(headers)}, ensure_ascii=False,
                      sort_keys=True, separators=(",", ":"))


def emit_insert(table: str, columns: list[str], values: list[list[str]], updates: list[str],
                batch_size: int) -> None:
    for start in range(0, len(values), batch_size):
        batch = values[start:start + batch_size]
        print(f"INSERT INTO {table} ({','.join(columns)}) VALUES")
        print(",\n".join("(" + ",".join(row) + ")" for row in batch))
        print("ON DUPLICATE KEY UPDATE " + ",".join(f"{column}=VALUES({column})" for column in updates) + ";")


def salesperson_values(tenant_id: uuid.UUID, rows: list[dict[str, str]], now: datetime) -> list[list[str]]:
    accepted: list[dict[str, str]] = []
    rejected = Counter()
    for row in rows:
        name = row["销售姓名"]
        city = row["城市"]
        employment_status = row["在职状态"]
        if employment_status == "离职":
            rejected["离职"] += 1
            continue
        if not name or not city or not employment_status:
            rejected["必填为空"] += 1
            continue
        if city not in CITY_ALLOWLIST:
            rejected["未知城市"] += 1
            continue
        accepted.append(row)
    accepted.sort(key=lambda item: (item["城市"], item["销售姓名"]))
    values: list[list[str]] = []
    for order, row in enumerate(accepted, start=1):
        canonical = "|".join((row["销售姓名"], row["城市"], row["职位"], row["在职状态"]))
        record_source_id = source_id("feishu-sales-csv", canonical)
        record_id = stable_uuid(tenant_id, "salesperson", record_source_id)
        values.append([
            sql_uuid(record_id), sql_uuid(tenant_id), sql_required_text(record_source_id),
            sql_required_text(row["销售姓名"]), sql_required_text(row["城市"]),
            sql_text(row["职位"]), sql_required_text(row["在职状态"]), sql_required_text("ACTIVE"),
            str(order), sql_datetime(now), sql_datetime(now),
        ])
    print(f"salespersons accepted={len(values)} rejected={sum(rejected.values())} reasons={dict(rejected)}",
          file=sys.stderr)
    return values


def store_values(tenant_id: uuid.UUID, rows: list[dict[str, str]], now: datetime) -> list[list[str]]:
    occurrences: Counter[str] = Counter()
    values: list[list[str]] = []
    rejected = Counter()
    for row in rows:
        required = ("城市", "属性", "名称", "营业状态", "联系人", "面积", "设施数",
                    "经营类型", "意向业务", "合作意向", "门店标签", "地理位置")
        if any(not row.get(key, "") for key in required):
            rejected["必填为空"] += 1
            continue
        if row["城市"] not in CITY_ALLOWLIST:
            rejected["未知城市"] += 1
            continue
        canonical = canonical_row(row, STORE_HEADERS)
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        occurrences[digest] += 1
        record_source_id = source_id("feishu-store-csv", canonical, occurrences[digest])
        record_id = stable_uuid(tenant_id, "store", record_source_id)
        client_store_id = stable_uuid(tenant_id, "store-client", record_source_id)
        created_at = parse_feishu_time(row["创建时间"], now)
        status = "INACTIVE" if row["营业状态"] == "倒闭" else "ACTIVE"
        values.append([
            sql_uuid(record_id), sql_uuid(tenant_id), sql_uuid(client_store_id),
            sql_required_text(record_source_id), sql_required_text(row["城市"]), "NULL",
            sql_required_text(row["属性"]), sql_required_text(row["名称"]),
            sql_required_text(row["营业状态"]), sql_required_text(row["联系人"]),
            sql_text(row["联系方式"]), sql_required_text(row["面积"]),
            sql_required_text(row["设施数"]),
            sql_required_text(json.dumps(split_multi(row["经营类型"]), ensure_ascii=False)),
            sql_required_text(json.dumps(split_multi(row["意向业务"]), ensure_ascii=False)),
            sql_required_text(row["合作意向"]), sql_text(row["门店等级"]),
            sql_required_text(json.dumps(split_multi(row["门店标签"]), ensure_ascii=False)),
            "NULL", "NULL", "NULL", "NULL", sql_required_text(row["地理位置"]),
            sql_required_text(status), sql_datetime(created_at), sql_datetime(now),
        ])
    print(f"stores accepted={len(values)} rejected={sum(rejected.values())} reasons={dict(rejected)}",
          file=sys.stderr)
    return values


def main() -> int:
    args = parse_args()
    if args.batch_size < 1 or args.batch_size > 500:
        raise ValueError("batch-size必须在1到500之间")
    salespersons = read_csv(args.salespersons_csv, SALESPERSON_HEADERS)
    stores = read_csv(args.stores_csv, STORE_HEADERS)
    now = datetime.now(timezone.utc)
    salesperson_rows = salesperson_values(args.tenant_id, salespersons, now)
    store_rows = store_values(args.tenant_id, stores, now)
    if not salesperson_rows or not store_rows:
        raise ValueError("导入数据为空，拒绝生成SQL")

    print("SET NAMES utf8mb4;")
    print("SET time_zone='+00:00';")
    print("START TRANSACTION;")
    emit_insert(
        "temp_sales_checkin_salesperson",
        ["id", "tenant_id", "source_record_id", "name", "city", "position", "employment_status",
         "status", "sort_order", "created_at", "updated_at"],
        salesperson_rows,
        ["name", "city", "position", "employment_status", "status", "sort_order", "updated_at"],
        args.batch_size,
    )
    emit_insert(
        "temp_sales_checkin_store",
        ["id", "tenant_id", "client_store_id", "source_record_id", "city", "creator_salesperson_id",
         "attribute", "name", "operating_status", "contact_name", "contact_phone", "area_range",
         "facility_count", "business_types_json", "intended_businesses_json", "cooperation_intent",
         "store_grade", "tags_json", "longitude", "latitude", "accuracy_meters", "location_captured_at",
         "location_note", "status", "created_at", "updated_at"],
        store_rows,
        ["city", "creator_salesperson_id", "attribute", "name", "operating_status", "contact_name",
         "contact_phone", "area_range", "facility_count", "business_types_json",
         "intended_businesses_json", "cooperation_intent", "store_grade", "tags_json", "location_note",
         "status", "updated_at"],
        args.batch_size,
    )
    print("COMMIT;")
    print("SELECT 'salespersons' AS dataset, COUNT(*) AS imported_count "
          f"FROM temp_sales_checkin_salesperson WHERE tenant_id={sql_uuid(args.tenant_id)};")
    print("SELECT 'stores' AS dataset, COUNT(*) AS imported_count "
          f"FROM temp_sales_checkin_store WHERE tenant_id={sql_uuid(args.tenant_id)};")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, csv.Error) as error:
        print(f"import failed: {error}", file=sys.stderr)
        raise SystemExit(2)
