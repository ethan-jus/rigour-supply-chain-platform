#!/usr/bin/env python3
"""One-time Sales -> CRM visit-policy initialization; dry-run by default.

Read source tables, preserve policy IDs/thresholds/effective periods, translate
Sales profile scope to the already-bound HR employee ID. No customer mapping.
Requires PyYAML + PyMySQL. Credentials stay in caller-provided config files.
"""
import argparse
import json
import re
from pathlib import Path

import pymysql
import yaml


def connect(path):
    cfg = yaml.safe_load(Path(path).read_text())["spring"]["datasource"]
    match = re.fullmatch(r"jdbc:mysql://([^:/]+):(\d+)/([^?]+)(?:\?.*)?", cfg["url"])
    if not match or "${" in str(cfg):
        raise ValueError("Use a resolved, local connection config; never pass credentials as arguments")
    return pymysql.connect(host=match[1], port=int(match[2]), database=match[3],
                           user=cfg["username"], password=str(cfg["password"]),
                           charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor)


def collect(source):
    with source.cursor() as c:
        c.execute("""SELECT BIN_TO_UUID(v.tenant_id) tenant,BIN_TO_UUID(v.id) id,
                     p.policy_name,v.* FROM sales_visit_policy_version v JOIN sales_visit_policy p
                     ON p.tenant_id=v.tenant_id AND p.id=v.policy_id""")
        policies = []
        for row in c.fetchall():
            policy = dict(id=row["id"], radiusMeters=row["check_in_radius_meters"],
                          minimumDwellMinutes=row["minimum_dwell_minutes"], requiredPhotoCount=row["required_photo_count"],
                          recordingEnabled=bool(row["recording_enabled"]), minimumRecordingSeconds=row["minimum_recording_seconds"],
                          maximumClipGapSeconds=row["maximum_clip_gap_seconds"], aiAsrEnabled=bool(row["ai_asr_enabled"]),
                          aiRelevanceEnabled=bool(row["ai_relevance_enabled"]), aiDuplicateEnabled=bool(row["ai_duplicate_enabled"]),
                          aiAutoConfirmThreshold=float(row["ai_auto_confirm_threshold"]) if row["ai_auto_confirm_threshold"] is not None else None)
            policies.append((row["tenant"], row["id"], row["version_no"], row["policy_name"], row["publish_status"],
                             row["effective_from"], row["effective_to"], json.dumps(policy, ensure_ascii=False)))
        c.execute("""SELECT BIN_TO_UUID(s.tenant_id) tenant,BIN_TO_UUID(s.policy_version_id) policy,
                     s.scope_type,s.effective_from,s.effective_to,p.hr_employee_id
                     FROM sales_policy_scope s LEFT JOIN sales_profile p
                     ON p.tenant_id=s.tenant_id AND p.id=s.scope_id
                     WHERE s.policy_type='VISIT' AND s.status='ACTIVE'""")
        scopes = []
        for row in c.fetchall():
            if row["scope_type"] == "ALL":
                key = ""
            elif row["scope_type"] == "EMPLOYEE" and row["hr_employee_id"] is not None:
                key = str(row["hr_employee_id"])
            else:
                raise ValueError("Scope requires an explicit HR department/area mapping: " + row["scope_type"])
            scopes.append((row["tenant"], row["policy"], row["scope_type"], key, row["effective_from"], row["effective_to"]))
        return policies, scopes


def apply(target, policies, scopes):
    with target.cursor() as c:
        # Published versions are immutable. Never overwrite a changed CRM version.
        for row in policies:
            c.execute("SELECT policy_json FROM crm_visit_policy_version WHERE tenant_id=%s AND id=%s", row[:2])
            old = c.fetchone()
            if old and json.loads(old["policy_json"]) != json.loads(row[-1]):
                raise ValueError("Existing CRM policy differs; refuse silent overwrite")
            c.execute("""INSERT INTO crm_visit_policy_version(tenant_id,id,version_no,policy_name,status_code,
                         effective_from,effective_to,policy_json,created_by) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,'SALES_POLICY_IMPORT')
                         ON DUPLICATE KEY UPDATE id=id""", row)
        for row in scopes:
            c.execute("""INSERT INTO crm_visit_policy_scope(tenant_id,policy_id,scope_type,scope_key,effective_from,effective_to)
                         VALUES(%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE policy_id=policy_id""", row)
        c.execute("""UPDATE crm_visit v JOIN crm_visit_policy_version p
                     ON p.tenant_id=v.tenant_id AND p.id=v.validation_policy_version
                     SET v.policy_snapshot=p.policy_json WHERE v.policy_snapshot IS NULL""")
        updated = c.rowcount
    target.commit()
    return updated


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--sales-config", required=True)
    args.add_argument("--crm-config", required=True)
    args.add_argument("--apply", action="store_true")
    cfg = args.parse_args()
    with connect(cfg.sales_config) as source:
        policies, scopes = collect(source)
    result = dict(policies=len(policies), scopes=len(scopes), applied=cfg.apply)
    if cfg.apply:
        with connect(cfg.crm_config) as target:
            result["visit_snapshots_filled"] = apply(target, policies, scopes)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
