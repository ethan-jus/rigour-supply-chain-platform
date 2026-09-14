package com.rigour.analytics.infrastructure.persistence.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** 复核采集专用映射；跨库只发生在显式采集事务，分页查看仅读取本地证据。 */
@Mapper
public interface BiReconciliationReviewMapper {
    @Select("""
            SELECT BIN_TO_UUID(id) AS captureId, source_id AS sourceId, source_name AS sourceName,
                   source_url AS sourceUrl, started_at AS startedAt, completed_at AS completedAt,
                   complete, filtered, checksum, record_count AS recordCount, page_count AS pageCount,
                   OCTET_LENGTH(payload_json) AS payloadBytes,
                   CASE WHEN OCTET_LENGTH(payload_json) <= #{maxPayloadBytes} THEN payload_json END AS payloadJson
              FROM rigour_integration.integration_feishu_online_capture
             WHERE tenant_id = UUID_TO_BIN(#{tenant}) AND id = UUID_TO_BIN(#{captureId})
               AND complete = TRUE AND completed_at IS NOT NULL
            """)
    Map<String, Object> onlineCapture(@Param("tenant") String tenant, @Param("captureId") String captureId,
                                      @Param("maxPayloadBytes") int maxPayloadBytes);

    @Select("""
            SELECT BIN_TO_UUID(id) AS batchId, original_file_name AS fileName, file_sha256 AS checksum,
                   source_url AS sourceUrl, created_at AS uploadedAt, status AS importStatus, total_rows AS rowCount
              FROM rigour_integration.integration_feishu_import_batch
             WHERE tenant_id = UUID_TO_BIN(#{tenant}) AND id = UUID_TO_BIN(#{batchId})
            """)
    Map<String, Object> version(@Param("tenant") String tenant, @Param("batchId") String batchId);

    @Select("""
            SELECT table_code AS tableCode, source_document_no AS sourceDocumentNo,
                   source_created_at AS sourceCreatedAt, row_json AS valuesJson,
                   sheet_name AS sheetName, source_row_number AS rowNumber, projection_status AS projectionStatus,
                   error_code AS errorCode, raw_row_hash AS rowHash
              FROM rigour_integration.integration_feishu_import_raw_row
             WHERE tenant_id = UUID_TO_BIN(#{tenant}) AND batch_id = UUID_TO_BIN(#{batchId})
               AND table_code IN ('FEISHU_SALES_ORDER', 'FEISHU_SALES_ORDER_LINE')
             ORDER BY table_code, source_row_number, id LIMIT #{limit}
            """)
    List<Map<String, Object>> sourceRows(@Param("tenant") String tenant, @Param("batchId") String batchId,
                                       @Param("limit") int limit);

    @Select("""
            SELECT o.source_order_no AS orderNo, 'ORDER' AS kind, o.source_order_no AS identityKey,
                   ca.area_name AS city,
                   COALESCE(o.owner_employee_name_snapshot, o.owner_sales_name,
                            c.owner_employee_name_snapshot, c.owner_sales_name) AS sales,
                   COALESCE(o.customer_name_snapshot, c.customer_name) AS customer,
                   o.order_date AS orderDate, o.payable_amount AS amount, o.paid_amount AS paid,
                   o.unpaid_amount AS unpaid, o.total_quantity AS quantity, o.updated_time AS updatedAt,
                   NULL AS product, NULL AS specification, NULL AS unit
              FROM rigour_order.order_sales_order o
              LEFT JOIN rigour_crm.crm_customer c ON c.tenant_id=o.tenant_id AND c.id=o.customer_id
              LEFT JOIN rigour_crm.crm_customer_area ca ON ca.tenant_id=UUID_TO_BIN(o.tenant_id)
               AND ca.area_code=COALESCE(NULLIF(o.region_code,''), NULLIF(c.region_code,''))
             WHERE o.tenant_id=#{tenant} AND o.source_system_code='FEISHU'
               AND o.deleted=0 AND o.order_status_code <> 'CANCELLED'
             ORDER BY o.source_order_no, o.id LIMIT #{limit}
            """)
    List<Map<String, Object>> businessOrders(@Param("tenant") String tenant, @Param("limit") int limit);

    @Select("""
            SELECT o.source_order_no AS orderNo, 'SKU' AS kind, l.id AS systemLineId,
                   l.product_variant_id AS systemVariantId,
                   CASE WHEN l.product_id IS NULL OR l.product_variant_id IS NULL THEN CONCAT('LINE:',l.id)
                        ELSE COALESCE(NULLIF(l.sku_code_snapshot,''), CONCAT('VARIANT:',l.product_variant_id)) END AS identityKey,
                   CASE WHEN l.product_id IS NULL OR l.product_variant_id IS NULL THEN 'UNLINKED' ELSE 'SYSTEM' END AS associationEvidence,
                   ca.area_name AS city,
                   COALESCE(o.owner_employee_name_snapshot, o.owner_sales_name,
                            c.owner_employee_name_snapshot, c.owner_sales_name) AS sales,
                   COALESCE(o.customer_name_snapshot,c.customer_name) AS customer, o.order_date AS orderDate,
                   l.line_amount AS amount, NULL AS paid, NULL AS unpaid, l.quantity AS quantity,
                   l.updated_time AS updatedAt, l.product_name_snapshot AS product,
                   l.specification_snapshot AS specification,
                   l.unit_code AS unit
              FROM rigour_order.order_sales_order_line l
              JOIN rigour_order.order_sales_order o ON o.tenant_id=l.tenant_id AND o.id=l.order_id
              LEFT JOIN rigour_crm.crm_customer c ON c.tenant_id=o.tenant_id AND c.id=o.customer_id
              LEFT JOIN rigour_crm.crm_customer_area ca ON ca.tenant_id=UUID_TO_BIN(o.tenant_id)
               AND ca.area_code=COALESCE(NULLIF(o.region_code,''),NULLIF(c.region_code,''))
             WHERE l.tenant_id=#{tenant} AND o.source_system_code='FEISHU'
               AND l.deleted=0 AND o.deleted=0 AND o.order_status_code <> 'CANCELLED'
             ORDER BY o.source_order_no, l.id LIMIT #{limit}
            """)
    List<Map<String, Object>> businessLines(@Param("tenant") String tenant, @Param("limit") int limit);

    @Select("""
            SELECT source_order_no AS orderNo, 'ORDER' AS kind, source_order_no AS identityKey,
                   region_name AS city, owner_staff_name AS sales, customer_name AS customer,
                   order_date AS orderDate, payable_amount AS amount, paid_amount AS paid,
                   unpaid_amount AS unpaid, total_quantity AS quantity, synced_time AS updatedAt,
                   NULL AS product, NULL AS specification, NULL AS unit
              FROM bi_sales_order_fact
             WHERE tenant_id=#{tenant} AND source_system_code='FEISHU'
               AND deleted=0 AND order_status_code <> 'CANCELLED'
             ORDER BY source_order_no, order_id LIMIT #{limit}
            """)
    List<Map<String, Object>> biOrders(@Param("tenant") String tenant, @Param("limit") int limit);

    @Select("""
            SELECT l.source_order_no AS orderNo, 'SKU' AS kind, l.order_line_id AS systemLineId,
                   l.product_variant_id AS systemVariantId,
                   CASE WHEN l.product_id IS NULL OR l.product_variant_id IS NULL THEN CONCAT('LINE:',l.order_line_id)
                        ELSE COALESCE(NULLIF(l.sku_code,''),CONCAT('VARIANT:',l.product_variant_id)) END AS identityKey,
                   CASE WHEN l.product_id IS NULL OR l.product_variant_id IS NULL THEN 'UNLINKED' ELSE 'SYSTEM' END AS associationEvidence,
                   l.region_name AS city, l.owner_staff_name AS sales, l.customer_name AS customer,
                   l.order_date AS orderDate, l.line_amount AS amount, NULL AS paid, NULL AS unpaid,
                   l.quantity AS quantity, l.synced_time AS updatedAt, l.product_name AS product,
                   l.specification_snapshot AS specification, l.unit_code AS unit
              FROM bi_sales_order_line_fact l
             WHERE l.tenant_id=#{tenant} AND l.source_system_code='FEISHU'
               AND l.deleted=0 AND l.order_status_code <> 'CANCELLED'
             ORDER BY l.source_order_no, l.order_line_id LIMIT #{limit}
            """)
    List<Map<String, Object>> biLines(@Param("tenant") String tenant, @Param("limit") int limit);

    @Insert("""
            INSERT INTO bi_reconciliation_review
              (id,tenant_id,actor_id,source_batch_id,captured_time,completed_time,review_json)
            VALUES (#{id},#{tenant},#{actor},#{batchId},#{capturedAt},#{completedAt},#{json})
            """)
    void insert(@Param("tenant") String tenant, @Param("actor") String actor, @Param("id") String id,
                @Param("batchId") String batchId, @Param("capturedAt") LocalDateTime capturedAt,
                @Param("completedAt") LocalDateTime completedAt, @Param("json") String json);

    @Select("""
            SELECT review_json FROM bi_reconciliation_review
             WHERE tenant_id=#{tenant} AND actor_id=#{actor} AND id=#{id}
            """)
    String find(@Param("tenant") String tenant, @Param("actor") String actor, @Param("id") String id);

    @Select("""
            SELECT id, JSON_UNQUOTE(JSON_EXTRACT(review_json,'$.sourceVersion.fileName')) AS fileName,
                   captured_time AS capturedAt, JSON_UNQUOTE(JSON_EXTRACT(review_json,'$.status')) AS status
              FROM bi_reconciliation_review WHERE tenant_id=#{tenant} AND actor_id=#{actor}
             ORDER BY captured_time DESC, id DESC LIMIT 30
            """)
    List<Map<String,Object>> history(@Param("tenant") String tenant, @Param("actor") String actor);
}
