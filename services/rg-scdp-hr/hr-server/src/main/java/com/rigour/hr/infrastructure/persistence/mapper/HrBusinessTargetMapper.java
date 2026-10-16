package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Subject;
import com.rigour.hr.infrastructure.persistence.entity.HrBusinessTargetEntity;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface HrBusinessTargetMapper extends BaseMapper<HrBusinessTargetEntity> {
    @Select("SELECT employee_code FROM hr_employee ${ew.customSqlSegment}")
    List<String> permittedEmployees(@Param(Constants.WRAPPER) Wrapper<?> query);

    @Select("SELECT department_code FROM hr_department ${ew.customSqlSegment}")
    List<String> permittedDepartments(@Param(Constants.WRAPPER) Wrapper<?> query);

    String SALES_DEPARTMENT =
            """
            SELECT d.id FROM hr_department d
            WHERE d.tenant_id=#{tenant} AND d.deleted=0 AND d.status_code='ACTIVE'
              AND EXISTS(SELECT 1 FROM hr_department_closure c JOIN hr_department root
                  ON root.tenant_id=c.tenant_id AND root.id=c.ancestor_id
                WHERE c.tenant_id=d.tenant_id AND c.descendant_id=d.id
                  AND root.department_name='销售部' AND root.deleted=0 AND root.status_code='ACTIVE')
            """;

    @Select(
            """
            SELECT 'CITY' AS dimension_type,department_code AS code,department_name AS name,
                   department_code AS city_code,department_name AS city_name,
                   NULL AS department_name,NULL AS employment_status,FALSE AS writable
            FROM hr_department WHERE tenant_id=#{tenant} AND id IN (
            """
                    + SALES_DEPARTMENT
                    + ") AND department_name<>'销售部' ORDER BY department_name,department_code")
    List<Subject> cities(@Param("tenant") String tenant);

    @Select(
            """
SELECT 'SALES_OWNER' AS dimension_type,e.employee_code AS code,e.employee_name AS name,
       d.department_code AS city_code,d.department_name AS city_name,
       d.department_name,e.employment_status,FALSE AS writable
FROM hr_employee e JOIN hr_department d ON d.tenant_id=e.tenant_id AND d.id=e.department_id
WHERE e.tenant_id=#{tenant} AND e.deleted=0 AND e.department_id IN (
"""
                    + SALES_DEPARTMENT
                    + ") ORDER BY d.department_name,e.employee_name,e.employee_code")
    List<Subject> salesOwners(@Param("tenant") String tenant);
}
