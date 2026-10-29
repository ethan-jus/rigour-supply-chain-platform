#!/usr/bin/env python3
"""Validate a sourced administrative catalog; preview by default, never overwrite a version."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
from datetime import date
from urllib.parse import urlsplit


# Business grouping confirmed by the user; administrative names come from the supplied source.
REGIONS = {
    "11": "NORTH", "12": "NORTH", "13": "NORTH", "14": "NORTH", "15": "NORTH",
    "21": "NORTHEAST", "22": "NORTHEAST", "23": "NORTHEAST",
    "31": "EAST", "32": "EAST", "33": "EAST", "34": "EAST", "35": "EAST", "36": "EAST", "37": "EAST", "71": "EAST",
    "41": "CENTRAL", "42": "CENTRAL", "43": "CENTRAL",
    "44": "SOUTH", "45": "SOUTH", "46": "SOUTH", "81": "SOUTH", "82": "SOUTH",
    "50": "SOUTHWEST", "51": "SOUTHWEST", "52": "SOUTHWEST", "53": "SOUTHWEST", "54": "SOUTHWEST",
    "61": "NORTHWEST", "62": "NORTHWEST", "63": "NORTHWEST", "64": "NORTHWEST", "65": "NORTHWEST",
}
LEVELS = {"PROVINCE": 0, "PREFECTURE": 1, "COUNTY": 2}


def text(value, label, maximum):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ValueError(f"{label}必填，且不超过{maximum}个字符")
    return value.strip()


def validate(payload):
    if not isinstance(payload, dict):
        raise ValueError("输入必须为JSON对象")
    version = text(payload.get("version"), "版本", 32)
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", version):
        raise ValueError("版本只能包含字母、数字、下划线、点或连字符")
    source_name = text(payload.get("sourceName"), "来源名称", 200)
    source_ref = text(payload.get("sourceRef"), "来源地址或文件标识", 500)
    effective = date.fromisoformat(text(payload.get("effectiveFrom"), "生效日期", 10))
    published = payload.get("sourcePublishedOn")
    published = date.fromisoformat(published) if published else None
    raw_nodes = payload.get("nodes")
    if not isinstance(raw_nodes, list) or not 1 <= len(raw_nodes) <= 10000:
        raise ValueError("区划节点数量须在1至10000之间")
    nodes = {}
    for raw in raw_nodes:
        if not isinstance(raw, dict):
            raise ValueError("区划节点须为对象")
        code = raw.get("code")
        if not isinstance(code, str) or not re.fullmatch(r"[0-9]{6}", code) or code[:2] not in REGIONS:
            raise ValueError("行政编码须为合法省级前缀的六位字符串")
        if code in nodes:
            raise ValueError(f"重复行政编码：{code}")
        name = text(raw.get("name"), f"{code}名称", 100)
        level = raw.get("level")
        if level not in LEVELS:
            raise ValueError(f"{code}行政等级应为PROVINCE、PREFECTURE或COUNTY")
        parent = raw.get("parentCode")
        if level == "PROVINCE":
            if parent is not None or not code.endswith("0000"):
                raise ValueError(f"{code}省级节点不能有上级，编码应以0000结尾")
            region = REGIONS[code[:2]]
            if raw.get("regionCode") not in (None, region):
                raise ValueError(f"{code}与已确认七大区域不一致")
        else:
            if not isinstance(parent, str) or not re.fullmatch(r"[0-9]{6}", parent) or parent == code:
                raise ValueError(f"{code}缺少合法上级编码")
            if raw.get("regionCode") is not None:
                raise ValueError(f"{code}的区域应从省级节点继承")
            if level == "PREFECTURE" and (not code.endswith("00") or code.endswith("0000")):
                raise ValueError(f"{code}地级编码格式无效")
            if level == "COUNTY" and code.endswith("00"):
                raise ValueError(f"{code}不是县级编码")
            region = None
        nodes[code] = {"code": code, "name": name, "level": level, "parentCode": parent, "regionCode": region}
    for code, node in nodes.items():
        if node["level"] == "PROVINCE":
            continue
        parent = nodes.get(node["parentCode"])
        if parent is None or parent["code"][:2] != code[:2]:
            raise ValueError(f"{code}上级缺失或跨省")
        expected = {"PROVINCE"} if node["level"] == "PREFECTURE" else {"PROVINCE", "PREFECTURE"}
        if parent["level"] not in expected:
            raise ValueError(f"{code}上级行政等级无效")
    return {
        "version": version, "sourceName": source_name, "sourceRef": source_ref,
        "effectiveFrom": effective, "sourcePublishedOn": published,
        "nodes": sorted(nodes.values(), key=lambda n: (LEVELS[n["level"]], n["code"])),
    }


def persist(connection, catalog, actor, publish):
    """One transaction, preserving all published/historical versions and source metadata."""
    try:
        connection.begin()
        with connection.cursor() as cursor:
            # Serialize initial publication, including the empty-catalog case under MySQL RR.
            cursor.execute("SELECT version_code,status_code FROM admin_division_version ORDER BY version_code FOR UPDATE")
            versions = cursor.fetchall()
            if any(row[0].casefold() == catalog["version"].casefold() for row in versions):
                raise ValueError("版本已存在，不允许覆盖；请使用新的来源版本")
            if publish and any(row[1] == "PUBLISHED" for row in versions):
                raise ValueError("已有发布版本；新版本须先完成门店和部门地域映射迁移，本工具只支持首次发布")
            cursor.execute("""INSERT INTO admin_division_version
                (version_code,source_name,source_ref,source_published_on,effective_from,status_code,created_at,created_by)
                VALUES(%s,%s,%s,%s,%s,'DRAFT',UTC_TIMESTAMP(6),%s)""",
                (catalog["version"], catalog["sourceName"], catalog["sourceRef"], catalog["sourcePublishedOn"], catalog["effectiveFrom"], actor))
            cursor.executemany("""INSERT INTO admin_division
                (version_code,admin_code,admin_name,admin_level,parent_code,region_code,created_at,created_by)
                VALUES(%s,%s,%s,%s,%s,%s,UTC_TIMESTAMP(6),%s)""",
                [(catalog["version"], n["code"], n["name"], n["level"], n["parentCode"], n["regionCode"], actor) for n in catalog["nodes"]])
            if publish:
                cursor.execute("UPDATE admin_division_version SET status_code='PUBLISHED',published_at=UTC_TIMESTAMP(6),published_by=%s,revision=revision+1 WHERE version_code=%s AND status_code='DRAFT'", (actor, catalog["version"]))
        connection.commit()
    except Exception:
        connection.rollback()
        raise


def connect(config_path):
    import yaml
    import pymysql
    config = yaml.safe_load(Path(config_path).read_text(encoding="utf-8"))["spring"]["datasource"]

    def resolve(value):
        def replace(match):
            key, separator, default = match.group(1).partition(":")
            result = os.environ.get(key, default if separator else None)
            if result is None:
                raise ValueError(f"数据源所需环境变量{key}未设置")
            return result
        return re.sub(r"\$\{([^}]+)}", replace, str(value))

    url = resolve(config["url"])
    if not url.startswith("jdbc:mysql://"):
        raise ValueError("只接受明确的MySQL JDBC数据源配置")
    parsed = urlsplit(url.removeprefix("jdbc:"))
    db = pymysql.connect(host=parsed.hostname, port=parsed.port or 3306, database=parsed.path.lstrip("/"),
                         user=resolve(config["username"]), password=resolve(config["password"]),
                         charset="utf8mb4", autocommit=False, connect_timeout=5, read_timeout=30, write_timeout=30)
    with db.cursor() as cursor:
        cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
    return db


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--apply", action="store_true", help="导入新版本；默认只校验预览")
    parser.add_argument("--publish", action="store_true", help="导入并首次发布；必须同时指定--apply")
    parser.add_argument("--config", type=Path, help="明确指定Foundation数据源YAML，不自动猜环境")
    parser.add_argument("--actor", help="执行人的账号标识，用于来源与发布审计")
    parser.add_argument("--expected-sha256", help="apply必须与已审阅文件SHA256一致")
    args = parser.parse_args()
    try:
        raw = args.source.read_bytes()
        if len(raw) > 5 * 1024 * 1024:
            raise ValueError("来源文件不得超过5MiB")
        digest = hashlib.sha256(raw).hexdigest()
        catalog = validate(json.loads(raw))
        if args.publish and not args.apply:
            raise ValueError("发布需要显式指定--apply")
        if args.apply:
            if not args.config or not args.actor or args.expected_sha256 != digest:
                raise ValueError("导入需要--config、--actor及与预览一致的--expected-sha256")
            actor = text(args.actor, "操作人", 64)
            connection = connect(args.config)
            try:
                persist(connection, catalog, actor, args.publish)
            finally:
                connection.close()
        print(json.dumps({"mode": "PUBLISHED" if args.publish else "DRAFT" if args.apply else "PREVIEW",
                          "version": catalog["version"], "sourceName": catalog["sourceName"], "sourceRef": catalog["sourceRef"],
                          "sha256": digest, "counts": {level: sum(n["level"] == level for n in catalog["nodes"]) for level in LEVELS}}, ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError) as error:
        parser.exit(2, f"校验失败：{error}\n")
    except Exception as error:
        # Database exceptions can include connection details; never echo raw credentials or DSNs.
        parser.exit(2, f"导入失败，事务已回滚（{type(error).__name__}）；请检查目标库及迁移状态。\n")


if __name__ == "__main__":
    main()
