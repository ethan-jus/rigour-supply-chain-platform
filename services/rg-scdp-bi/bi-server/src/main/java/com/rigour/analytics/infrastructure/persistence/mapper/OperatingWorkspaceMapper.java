package com.rigour.analytics.infrastructure.persistence.mapper;

import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore.ActionFilter;
import com.rigour.analytics.application.port.out.OperatingWorkspaceStore.BusinessSubject;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 仅访问rigour_bi自有表；每个查询和条件更新均携带tenant_id。 */
public interface OperatingWorkspaceMapper {
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
