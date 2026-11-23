package com.rigour.hr.infrastructure.persistence.repository;

import com.rigour.hr.api.v1.model.HrFieldworkOrganization;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Repository
public class FieldworkOrganizationQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public FieldworkOrganizationQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> salesDepartments(String tenant) {
        return jdbc.query("""
                SELECT d.id,d.department_name FROM hr_department d
                WHERE d.tenant_id=:tenant AND d.status_code='ACTIVE' AND d.deleted=0
                  AND EXISTS (SELECT 1 FROM hr_department_closure c JOIN hr_department root
                      ON root.tenant_id=c.tenant_id AND root.id=c.ancestor_id
                      WHERE c.tenant_id=d.tenant_id AND c.descendant_id=d.id AND root.id<>d.id
                        AND root.department_name='销售部' AND root.status_code='ACTIVE' AND root.deleted=0)
                ORDER BY d.sort_order,d.id
                """, Map.of("tenant", tenant), rows -> {
            var result = new LinkedHashMap<String, String>();
            while (rows.next()) result.put(rows.getString("id"), rows.getString("department_name"));
            return result;
        });
    }

    @Transactional(readOnly = true)
    public List<HrFieldworkOrganization> find(String tenant, Instant at, List<String> codes) {
        if (codes.isEmpty()) return List.of();
        var params =
                new MapSqlParameterSource("tenant", tenant)
                        .addValue("at", LocalDateTime.ofInstant(at, ZoneOffset.UTC))
                        .addValue("codes", codes);
        var employees =
                jdbc.query(
                        """
SELECT e.employee_code,e.employee_name,
  (SELECT h.city_name FROM hr_employee_city_history h
   WHERE h.tenant_id=e.tenant_id COLLATE utf8mb4_general_ci
     AND h.employee_code=e.employee_code COLLATE utf8mb4_general_ci AND h.effective_at<=:at
   ORDER BY h.effective_at DESC LIMIT 1) city,
  COALESCE(a.department_id,IF(e.updated_time<=:at,e.department_id,NULL)) dept_id,
  COALESCE(a.department_name_snapshot,IF(e.updated_time<=:at,e.department_name_snapshot,NULL)) dept_name
FROM hr_employee e LEFT JOIN hr_employee_assignment a ON a.id=(
  SELECT ha.id FROM hr_employee_assignment ha WHERE ha.tenant_id=e.tenant_id AND ha.employee_code=e.employee_code
    AND ha.effective_from<=:at AND (ha.effective_to IS NULL OR ha.effective_to>:at)
  ORDER BY ha.effective_from DESC,ha.id DESC LIMIT 1)
WHERE e.tenant_id=:tenant AND e.employee_code IN (:codes)
""",
                        params,
                        (r, n) ->
                                new HrFieldworkOrganization(
                                        r.getString("employee_code"),
                                        r.getString("employee_name"),
                                        r.getString("city"),
                                        r.getObject("dept_id", Long.class),
                                        r.getString("dept_name"),
                                        Map.of()));
        var ids =
                employees.stream()
                        .map(HrFieldworkOrganization::departmentId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (ids.isEmpty()) return employees;
        var parents = new HashMap<Long, Map<Long, String>>();
        jdbc.query(
                """
WITH RECURSIVE versions AS (
    SELECT department_id,parent_id,department_name,ROW_NUMBER() OVER(PARTITION BY department_id ORDER BY effective_at DESC) rn
    FROM hr_department_parent_history WHERE tenant_id=:tenant AND effective_at<=:at
), lineage(leaf,department_id,parent_id,department_name) AS (
    SELECT department_id,department_id,parent_id,department_name FROM versions WHERE rn=1 AND department_id IN (:depts)
    UNION ALL SELECT l.leaf,p.department_id,p.parent_id,p.department_name FROM lineage l JOIN versions p ON p.department_id=l.parent_id AND p.rn=1
) SELECT leaf,department_id,department_name FROM lineage
""",
                params.addValue("depts", ids),
                (RowCallbackHandler)
                        r ->
                                parents.computeIfAbsent(r.getLong(1), k -> new LinkedHashMap<>())
                                        .put(r.getLong(2), r.getString(3)));
        return employees.stream()
                .map(
                        e ->
                                new HrFieldworkOrganization(
                                        e.employeeCode(),
                                        e.employeeName(),
                                        e.city(),
                                        e.departmentId(),
                                        parents.getOrDefault(e.departmentId(), Map.of())
                                                .getOrDefault(
                                                        e.departmentId() == null
                                                                ? -1L
                                                                : e.departmentId(),
                                                        e.departmentName()),
                                        parents.getOrDefault(e.departmentId(), Map.of())))
                .toList();
    }
}
