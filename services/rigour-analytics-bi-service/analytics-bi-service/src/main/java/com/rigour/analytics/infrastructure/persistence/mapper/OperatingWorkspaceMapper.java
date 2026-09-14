package com.rigour.analytics.infrastructure.persistence.mapper;

import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore.ActionFilter;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore.BusinessSubject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 仅访问rigour_bi自有表；每个查询和条件更新均携带tenant_id。 */
public interface OperatingWorkspaceMapper {
    String TARGET_COLUMNS = """
            TRIM(CAST(id AS CHAR(64))) AS id, SUBSTRING(CAST(target_month AS CHAR(10)), 1, 7) AS `month`,
            dimension_type AS dimensionType, dimension_code AS dimensionCode, dimension_name AS dimensionName,
            metric_code AS metricCode, target_value AS targetValue, remark, revision, updated_time AS updatedAt
            """;
    String ACTION_COLUMNS = """
            id, kind, business_ref AS businessRef, business_label AS businessLabel,
            city_code AS cityCode, employee_code AS employeeCode, assignee, due_at AS dueAt,
            status, note, revision, created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt
            """;
    String ACTION_WHERE = """
            tenant_id=#{tenant}
            <if test="f.kind != null">AND kind=#{f.kind}</if>
            <if test="f.businessRef != null">AND business_ref=#{f.businessRef}</if>
            <if test="f.cityCode != null">AND city_code=#{f.cityCode}</if>
            <if test="f.employeeCode != null">AND (employee_code=#{f.employeeCode} OR assignee=#{f.employeeCode})</if>
            <if test="f.assignee != null">AND assignee=#{f.assignee}</if>
            <if test="f.status != null">AND status=#{f.status}</if>
            <if test="!f.includeStock">AND kind &lt;&gt; 'STOCK'</if>
            """;

    @Select("<script>SELECT " + TARGET_COLUMNS + """
             FROM bi_business_target WHERE tenant_id=#{tenant} AND target_month=#{month} AND deleted=0
             <if test="type != null">AND dimension_type=#{type}</if>
             <if test="code != null">AND dimension_code=#{code}</if>
             <if test="owner != null">AND dimension_type='SALES_OWNER' AND dimension_code=#{owner}</if>
             <if test="region != null">
               AND ((dimension_type='CITY' AND dimension_code=#{region}) OR
                    (dimension_type='SALES_OWNER' AND dimension_code IN
                       (SELECT owner_staff_code FROM bi_customer_dim WHERE tenant_id=#{tenant} AND deleted=0 AND region_code=#{region}
                        UNION SELECT owner_staff_code FROM bi_sales_order_fact WHERE tenant_id=#{tenant} AND deleted=0 AND region_code=#{region})
                     AND dimension_code NOT IN
                       (SELECT owner_staff_code FROM bi_customer_dim WHERE tenant_id=#{tenant} AND deleted=0 AND owner_staff_code IS NOT NULL AND (region_code IS NULL OR region_code &lt;&gt; #{region})
                        UNION SELECT owner_staff_code FROM bi_sales_order_fact WHERE tenant_id=#{tenant} AND deleted=0 AND owner_staff_code IS NOT NULL AND (region_code IS NULL OR region_code &lt;&gt; #{region}))))
             </if>
             ORDER BY dimension_type, dimension_name, metric_code
             </script>
            """)
    List<TargetView> targets(@Param("tenant") String tenant, @Param("month") LocalDate month,
            @Param("type") String type, @Param("code") String code, @Param("region") String region, @Param("owner") String owner);

    @Select("SELECT " + TARGET_COLUMNS + " FROM bi_business_target WHERE tenant_id=#{tenant} AND id=#{id} AND deleted=0")
    TargetView target(@Param("tenant") String tenant, @Param("id") String id);

    @Select("""
            <script>
            SELECT DISTINCT region_code FROM (
                SELECT region_code, owner_staff_code FROM bi_customer_dim WHERE tenant_id=#{tenant} AND deleted=0
                UNION SELECT region_code, owner_staff_code FROM bi_sales_order_fact WHERE tenant_id=#{tenant} AND deleted=0
            ) dimensions WHERE region_code IS NOT NULL AND region_code &lt;&gt; ''
            <choose><when test="type == 'CITY'">AND region_code=#{code}</when>
                <otherwise>AND owner_staff_code=#{code}</otherwise></choose>
            </script>
            """)
    List<String> targetRegions(@Param("tenant") String tenant, @Param("type") String type, @Param("code") String code);

    @Select("""
            SELECT CONCAT('customer-id:', TRIM(CAST(customer_id AS CHAR(64)))) AS businessRef,
                COALESCE(NULLIF(customer_name, ''), '未命名客户') AS businessLabel,
                region_code AS cityCode, owner_staff_code AS employeeCode
              FROM bi_customer_dim WHERE tenant_id=#{tenant} AND deleted=0
                AND ((#{byId}=TRUE AND TRIM(CAST(customer_id AS CHAR(64)))=#{ref})
                     OR (#{byId}=FALSE AND COALESCE(customer_code, TRIM(CAST(customer_id AS CHAR(64))))=#{ref}))
            """)
    List<BusinessSubject> customerSubject(@Param("tenant") String tenant, @Param("ref") String ref, @Param("byId") boolean byId);
    @Select("""
            SELECT CONCAT('order-id:', TRIM(CAST(order_id AS CHAR(64)))) AS businessRef,
                COALESCE(source_order_no, order_no, '销售订单') AS businessLabel,
                region_code AS cityCode, owner_staff_code AS employeeCode
              FROM bi_sales_order_fact WHERE tenant_id=#{tenant} AND deleted=0 AND TRIM(CAST(order_id AS CHAR(64)))=#{ref}
            """)
    List<BusinessSubject> orderSubject(@Param("tenant") String tenant, @Param("ref") String ref);
    @Select("""
            SELECT CONCAT('product-code:', product_code) AS businessRef,
                COALESCE(MAX(product_name), '库存商品') AS businessLabel,
                CASE WHEN COUNT(DISTINCT region_code)=1 THEN MAX(region_code) ELSE NULL END AS cityCode,
                CAST(NULL AS CHAR) AS employeeCode
              FROM bi_inventory_balance_current WHERE tenant_id=#{tenant} AND deleted=0 AND product_code=#{ref}
              GROUP BY product_code
            """)
    List<BusinessSubject> stockSubject(@Param("tenant") String tenant, @Param("ref") String ref);

    @Select("SELECT " + TARGET_COLUMNS + """
             FROM bi_business_target WHERE tenant_id=#{tenant} AND target_month=#{month}
             AND dimension_type=#{c.dimensionType} AND dimension_code=#{c.dimensionCode} AND metric_code=#{c.metricCode}
            """)
    TargetView targetKey(@Param("tenant") String tenant, @Param("month") LocalDate month, @Param("c") TargetCommand command);

    @Insert("""
            INSERT INTO bi_business_target
                (tenant_id, target_month, dimension_type, dimension_code, dimension_name, metric_code,
                 target_value, source_system_code, remark, synced_time, revision, created_by, updated_by, created_time, updated_time)
            VALUES (#{tenant}, #{month}, #{c.dimensionType}, #{c.dimensionCode}, #{c.dimensionName}, #{c.metricCode},
                    #{c.targetValue}, 'BI_MANUAL', #{c.remark}, #{now}, 1, #{actor}, #{actor}, #{now}, #{now})
            """)
    int insertTarget(@Param("tenant") String tenant, @Param("actor") String actor, @Param("month") LocalDate month,
            @Param("c") TargetCommand command, @Param("now") Instant now);

    @Update("""
            UPDATE bi_business_target SET dimension_name=#{c.dimensionName}, target_value=#{c.targetValue},
                remark=#{c.remark}, source_system_code='BI_MANUAL', revision=revision+1, deleted=0,
                updated_by=#{actor}, synced_time=#{now}, updated_time=#{now}
             WHERE tenant_id=#{tenant} AND target_month=#{month} AND dimension_type=#{c.dimensionType}
               AND dimension_code=#{c.dimensionCode} AND metric_code=#{c.metricCode} AND revision=#{c.expectedRevision} AND deleted=0
            """)
    int updateTarget(@Param("tenant") String tenant, @Param("actor") String actor, @Param("month") LocalDate month,
            @Param("c") TargetCommand command, @Param("now") Instant now);

    @Update("""
            UPDATE bi_business_target SET dimension_name=#{c.dimensionName}, target_value=#{c.targetValue}, remark=#{c.remark},
                source_system_code='BI_MANUAL', revision=revision+1, deleted=0, updated_by=#{actor}, synced_time=#{now}, updated_time=#{now}
             WHERE tenant_id=#{tenant} AND target_month=#{month} AND dimension_type=#{c.dimensionType}
               AND dimension_code=#{c.dimensionCode} AND metric_code=#{c.metricCode} AND deleted=1
            """)
    int restoreTarget(@Param("tenant") String tenant, @Param("actor") String actor, @Param("month") LocalDate month,
            @Param("c") TargetCommand command, @Param("now") Instant now);

    @Update("""
            UPDATE bi_business_target SET deleted=1, revision=revision+1, updated_by=#{actor},
                updated_time=#{now}, synced_time=#{now}
             WHERE tenant_id=#{tenant} AND id=#{id} AND revision=#{revision} AND deleted=0
            """)
    int deleteTarget(@Param("tenant") String tenant, @Param("actor") String actor, @Param("id") String id,
            @Param("revision") int revision, @Param("now") Instant now);

    @Insert("""
            INSERT INTO bi_business_target_event
                (id, tenant_id, target_id, revision, target_value, dimension_name, remark, deleted, actor, occurred_at)
            SELECT #{eventId}, tenant_id, id, revision, target_value, dimension_name, remark, deleted, #{actor}, #{now}
              FROM bi_business_target WHERE tenant_id=#{tenant} AND id=#{id}
            """)
    int targetEvent(@Param("tenant") String tenant, @Param("actor") String actor, @Param("id") String id,
            @Param("eventId") String eventId, @Param("now") Instant now);

    @Select("<script>SELECT " + ACTION_COLUMNS + " FROM bi_operating_action WHERE " + ACTION_WHERE
            + " ORDER BY due_at, id LIMIT #{f.pageSize} OFFSET #{f.offset}</script>")
    List<ActionView> actions(@Param("tenant") String tenant, @Param("f") ActionFilter filter);
    @Select("<script>SELECT COUNT(*) FROM bi_operating_action WHERE " + ACTION_WHERE + "</script>")
    long actionCount(@Param("tenant") String tenant, @Param("f") ActionFilter filter);
    @Select("SELECT " + ACTION_COLUMNS + " FROM bi_operating_action WHERE tenant_id=#{tenant} AND id=#{id}")
    ActionView action(@Param("tenant") String tenant, @Param("id") String id);

    @Insert("""
            INSERT INTO bi_operating_action (id, tenant_id, kind, business_ref, business_label, city_code,
                employee_code, assignee, due_at, status, note, revision, created_by, created_at, updated_at)
            VALUES (#{id}, #{tenant}, #{c.kind}, #{c.businessRef}, #{c.businessLabel}, #{c.cityCode},
                #{c.employeeCode}, #{c.assignee}, #{c.dueAt}, 'OPEN', #{c.note}, 1, #{actor}, #{now}, #{now})
            """)
    int insertAction(@Param("tenant") String tenant, @Param("actor") String actor, @Param("id") String id,
            @Param("c") ActionCommand command, @Param("now") Instant now);
    @Update("""
            UPDATE bi_operating_action SET assignee=#{c.assignee}, due_at=#{c.dueAt}, status=#{c.status},
                note=#{c.note}, revision=revision+1, updated_at=#{now}
             WHERE tenant_id=#{tenant} AND id=#{id} AND revision=#{c.expectedRevision}
            """)
    int updateAction(@Param("tenant") String tenant, @Param("id") String id, @Param("c") ActionUpdateCommand command,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO bi_operating_action_event (id, tenant_id, action_id, revision, previous_status, status,
                previous_assignee, assignee, previous_due_at, due_at, note, actor, occurred_at)
            VALUES (#{e.id}, #{tenant}, #{e.actionId}, #{e.revision}, #{e.previousStatus}, #{e.status},
                #{e.previousAssignee}, #{e.assignee}, #{e.previousDueAt}, #{e.dueAt}, #{e.note}, #{e.actor}, #{e.occurredAt})
            """)
    int insertEvent(@Param("tenant") String tenant, @Param("e") ActionEventView event);
    @Select("""
            SELECT id, action_id AS actionId, revision, previous_status AS previousStatus, status,
                previous_assignee AS previousAssignee, assignee, previous_due_at AS previousDueAt,
                due_at AS dueAt, note, actor, occurred_at AS occurredAt
              FROM bi_operating_action_event WHERE tenant_id=#{tenant} AND action_id=#{id} ORDER BY revision DESC
            """)
    List<ActionEventView> events(@Param("tenant") String tenant, @Param("id") String id);
}
