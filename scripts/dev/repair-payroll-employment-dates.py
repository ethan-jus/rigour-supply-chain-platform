#!/usr/bin/env python3
"""Preview and apply evidence-backed HR first-assignment start repairs for one tenant."""

import argparse
from collections import Counter, defaultdict
from datetime import datetime, time, timezone
from getpass import getpass
import hashlib
import json
import os
from pathlib import Path
from zoneinfo import ZoneInfo

import pymysql
from pymysql.cursors import DictCursor


REPO = Path(__file__).resolve().parents[2]
SHANGHAI = ZoneInfo("Asia/Shanghai")
CREATION_EVENTS = {"EMPLOYEE_CREATE", "EMPLOYEE_SOURCE_CREATE", "EMPLOYEE_ROSTER_IMPORT"}
REGISTRATION_EVENTS = CREATION_EVENTS | {"EMPLOYEE_UPDATE"}
NORMAL_UPDATES = {"EMPLOYEE_UPDATE", "EMPLOYEE_SOURCE_UPDATE"}
ACTION = "FIRST_ASSIGNMENT_DATE_REPAIR"


def read_plan(cursor, args, applying):
    cursor.execute("SELECT DATABASE() db, @@server_uuid server_id")
    database_identity = cursor.fetchone()
    cursor.execute(
        "SELECT version FROM hr_organization_state WHERE tenant_id=%s"
        + (" FOR UPDATE" if applying else ""),
        (args.tenant,),
    )
    state = cursor.fetchone()
    if state is None:
        raise SystemExit("指定租户没有 HR 组织状态记录")

    cursor.execute(
        """
        WITH terms AS (
            SELECT employee_code, COUNT(*) term_count
              FROM hr_employee_assignment WHERE tenant_id=%s GROUP BY employee_code
        )
        SELECT e.employee_code,e.entry_date,e.employment_status,e.revision employee_revision,
               e.department_id current_department,e.primary_position_code current_position,
               COALESCE(t.term_count,0) term_count,
               a.id assignment_id,a.department_id,a.position_code,a.effective_from,a.effective_to,
               a.revision assignment_revision,a.actor_id assignment_actor
          FROM hr_employee e
          LEFT JOIN terms t ON t.employee_code COLLATE utf8mb4_general_ci=e.employee_code COLLATE utf8mb4_general_ci
          LEFT JOIN hr_employee_assignment a
            ON a.tenant_id COLLATE utf8mb4_general_ci=e.tenant_id COLLATE utf8mb4_general_ci
           AND a.employee_code COLLATE utf8mb4_general_ci=e.employee_code COLLATE utf8mb4_general_ci
           AND t.term_count=1
         WHERE e.tenant_id=%s AND e.deleted=0
         ORDER BY e.employee_code
        """,
        (args.tenant, args.tenant),
    )
    employees = cursor.fetchall()
    cursor.execute(
        """
        SELECT object_ref,version,UPPER(TRIM(event_type)) event_type,actor_id,created_time
          FROM hr_organization_change WHERE tenant_id=%s AND UPPER(TRIM(event_type)) LIKE %s
         ORDER BY version
        """,
        (args.tenant, "EMPLOYEE%"),
    )
    employee_events = defaultdict(list)
    for event in cursor.fetchall():
        employee_events[event["object_ref"]].append(event)

    counts = Counter(total=len(employees), eligible=0)
    repairs = []
    for employee in employees:
        reason = classify(employee, employee_events[employee["employee_code"]])
        counts[reason] += 1
        if reason == "eligible":
            repairs.append(employee)
    return {
        "target": {
            **database_identity, "host": args.host, "port": args.port, "tenant": args.tenant,
            "actor": args.actor, "reason": args.reason,
        },
        "organization_version": state["version"],
        "counts": dict(counts),
        "repairs": repairs,
    }


def classify(employee, events):
    if employee["term_count"] == 0:
        return "no_assignment_review"
    if employee["term_count"] > 1:
        return "multiple_assignments_review"
    if employee["entry_date"] is None:
        return "no_entry_date_review"
    local_date = employee["entry_date"].replace(tzinfo=timezone.utc).astimezone(SHANGHAI).date()
    entry = datetime.combine(local_date, time.min, SHANGHAI).astimezone(timezone.utc).replace(tzinfo=None)
    start = employee["effective_from"]
    if start == entry:
        return "already_aligned"
    if start < entry:
        return "starts_before_entry_review"
    if employee["effective_to"] is not None or employee["employment_status"] != "ACTIVE":
        return "closed_or_inactive_review"
    if (employee["department_id"] != employee["current_department"]
            or employee["position_code"] != employee["current_position"]):
        return "current_assignment_mismatch_review"

    creations = [event for event in events if event["event_type"] in CREATION_EVENTS]
    # The assignment write and organization audit share one transaction. A short allowance
    # accounts for separate statements without matching unrelated older operations.
    anchor = [
        event for event in events
        if event["event_type"] in REGISTRATION_EVENTS
        and event["actor_id"] == employee["assignment_actor"]
        and 0 <= (event["created_time"] - start).total_seconds() <= 10
    ]
    if len(creations) != 1 or len(anchor) != 1:
        return "ambiguous_creation_or_registration_review"
    creation, registration = creations[0], anchor[0]
    if creation["version"] > registration["version"] or creation["created_time"] > registration["created_time"]:
        return "ambiguous_creation_or_registration_review"
    intervening = any(
        event["version"] < registration["version"] and event != creation
        for event in events
    )
    other_later_events = any(
        event["version"] > registration["version"] and event["event_type"] not in NORMAL_UPDATES
        for event in events
    )
    if intervening or other_later_events:
        return "other_employment_events_review"

    employee["corrected_effective_from"] = entry
    employee["creation_event"] = creation
    employee["registration_event"] = registration
    return "eligible"


def json_text(value):
    return json.dumps(value, ensure_ascii=False, default=str, sort_keys=True)


def digest(plan):
    return hashlib.sha256(json_text(plan).encode("utf-8")).hexdigest()


def save_preview(backup, plan):
    # Exclusive creation protects the original preimage on retries.
    descriptor = os.open(backup, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o400)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(json_text({"digest": digest(plan), "plan": plan}))
        output.flush()
        os.fsync(output.fileno())


def apply_plan(cursor, args, plan):
    for row in plan["repairs"]:
        cursor.execute(
            """
            UPDATE hr_employee_assignment a JOIN hr_employee e
              ON e.tenant_id COLLATE utf8mb4_general_ci=a.tenant_id COLLATE utf8mb4_general_ci
             AND e.employee_code COLLATE utf8mb4_general_ci=a.employee_code COLLATE utf8mb4_general_ci
               SET a.effective_from=%s,a.revision=a.revision+1
             WHERE a.tenant_id=%s AND a.id=%s AND a.revision=%s
               AND a.effective_from=%s AND a.effective_to IS NULL
               AND e.deleted=0 AND e.revision=%s
            """,
            (row["corrected_effective_from"], args.tenant, row["assignment_id"],
             row["assignment_revision"], row["effective_from"], row["employee_revision"]),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("任职或员工档案版本已变更，已取消本批处理")
        cursor.execute("UPDATE hr_organization_state SET version=version+1 WHERE tenant_id=%s", (args.tenant,))
        cursor.execute(
            """
            INSERT INTO hr_organization_change(tenant_id,version,event_type,object_ref,actor_id)
            SELECT tenant_id,version,%s,%s,%s FROM hr_organization_state WHERE tenant_id=%s
            """,
            (f"EMPLOYEE_{ACTION}", row["employee_code"], args.actor, args.tenant),
        )
        cursor.execute(
            """
            INSERT INTO hr_payroll_audit(tenant_id,object_id,action_code,actor_id,reason,snapshot_json)
            VALUES(%s,%s,%s,%s,%s,%s)
            """,
            (args.tenant, f"EMPLOYEE:{row['employee_code']}", ACTION, args.actor, args.reason, json_text(row)),
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["preview", "apply"])
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--database", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--actor", required=True)
    parser.add_argument("--reason", required=True)
    parser.add_argument("--private-dir", required=True, type=Path)
    args = parser.parse_args()
    args.actor, args.reason, args.tenant = args.actor.strip(), args.reason.strip(), args.tenant.strip()
    if not (0 < len(args.tenant) <= 64 and 0 < len(args.actor) <= 50 and 0 < len(args.reason) <= 500):
        parser.error("请填写有效的租户、操作人和修正原因")
    private = args.private_dir.resolve(strict=True)
    if not private.is_dir() or private.is_relative_to(REPO):
        parser.error("备份目录须位于仓库外")
    backup = private / "employment-date-repair.json"
    applying = args.mode == "apply"
    if applying:
        if backup.is_symlink():
            raise SystemExit("备份文件不能是符号链接")
        with backup.open(encoding="utf-8") as original:
            verified = json.load(original)
        if digest(verified["plan"]) != verified["digest"]:
            raise SystemExit("预检备份的校验值不一致")
    password = os.environ.get("RIGOUR_HR_REPAIR_DB_PASSWORD")
    if password is None:
        password = getpass("数据库密码: ")
    with pymysql.connect(
        host=args.host, port=args.port, database=args.database, user=args.user, password=password,
        init_command="SET SESSION time_zone='+00:00'", cursorclass=DictCursor, autocommit=False,
    ) as db:
        with db.cursor() as cursor:
            if not applying:
                cursor.execute("START TRANSACTION READ ONLY")
            plan = read_plan(cursor, args, applying)
            if applying:
                if digest(plan) != verified["digest"]:
                    raise SystemExit("目标环境或候选证据已变化，请使用新的独立目录重新预检")
                apply_plan(cursor, args, plan)
                db.commit()
            else:
                save_preview(backup, plan)
                db.rollback()
            result = {"mode": args.mode, **plan["counts"]}
            if applying:
                result["applied"] = len(plan["repairs"])
            print(json_text(result))


if __name__ == "__main__":
    main()
