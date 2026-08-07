package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.domain.model.order.DhbOrderDocuments.FinancialDocument;
import com.rigour.order.domain.model.order.DhbOrderDocuments.ReturnDetail;
import com.rigour.order.domain.model.order.DhbOrderDocuments.ReturnDocument;
import com.rigour.order.domain.model.order.DhbOrderDocuments.Shipment;
import com.rigour.order.domain.model.order.DhbOrderDocuments.ShipmentDetail;
import java.time.LocalDateTime;
import java.util.List;

/** 发货、退货和收付款单据的订单中心持久化端口。 */
public interface OrderDocumentRepository {
    /** 幂等导入一批独立发货单；以tenantId+shipmentNo为唯一键。 */
    int importShipments(String tenantId, List<DhbOrderImportBatch.ShipmentItem> items);

    /** 幂等导入一批退货单；以tenantId+returnNo为唯一键。 */
    int importReturns(String tenantId, List<DhbOrderImportBatch.ReturnItem> items);

    /** 幂等导入收款/付款单；以tenantId+documentType+documentNo为唯一键。 */
    int importFinancialDocuments(String tenantId, List<DhbOrderImportBatch.FinancialItem> items);

    /** 按来源状态、出库类型、关联订单号和来源时间查询统一出库/发货单。 */
    List<Shipment> findShipments(String tenantId, DocumentFilter filter);

    /** 统计符合过滤条件的发货单总数。 */
    long countShipments(String tenantId, DocumentFilter filter);

    /** 按发货单号读取主表和明细；不存在时返回null。 */
    ShipmentDetail findShipment(String tenantId, String shipmentNo);

    /** 按来源状态、关联订单号和来源时间查询退货单。 */
    List<ReturnDocument> findReturns(String tenantId, DocumentFilter filter);

    /** 统计符合过滤条件的退货单总数。 */
    long countReturns(String tenantId, DocumentFilter filter);

    /** 按退货单号读取主表和明细；不存在时返回null。 */
    ReturnDetail findReturn(String tenantId, String returnNo);

    /** 查询RECEIPT收款单或PAYMENT付款单。 */
    List<FinancialDocument> findFinancialDocuments(String tenantId, String documentType,
                                                    DocumentFilter filter);

    /** 统计指定收付款类型的本地单据总数。 */
    long countFinancialDocuments(String tenantId, String documentType, DocumentFilter filter);

    /**
     * 本地分页过滤器。
     * status为订货宝来源状态；typeId为订货宝出库类型，支持逗号分隔；orderNo为关联订单号；
     * from/to均为来源业务时间，含边界。
     */
    record DocumentFilter(int begin, int step, String status, String typeId, String orderNo,
                          LocalDateTime from, LocalDateTime to) {
    }
}
