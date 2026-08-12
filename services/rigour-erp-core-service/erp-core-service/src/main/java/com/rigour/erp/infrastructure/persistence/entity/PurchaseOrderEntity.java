package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 采购单头实体；内部状态不由订货宝状态直接覆盖。 */
@TableName("erp_purchase_order")
public class PurchaseOrderEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String tenantId;
    public String purchaseOrderNo;
    public String sourcePurchaseId;
    public String sourceSupplierId;
    public String sourceWarehouseId;
    public String supplierId;
    public String warehouseId;
    public String supplierCodeSnapshot;
    public String supplierNameSnapshot;
    public String warehouseCodeSnapshot;
    public String warehouseNameSnapshot;
    public String staffSourceId;
    public String staffName;
    public String sourceStatus;
    public String sourceStatusName;
    public String sourcePaymentStatus;
    public String sourcePaymentName;
    public String internalStatus;
    public LocalDateTime deliveryAt;
    public LocalDateTime sourceCreatedAt;
    public LocalDateTime sourceUpdatedAt;
    public BigDecimal totalAmount;
    public BigDecimal paidAmount;
    public BigDecimal goodsCount;
    public Boolean sourceDownloaded;
    public String remark;
    public String internalCommunication;
    public String ownershipState;
    public String recordOrigin;
    public LocalDateTime sourceSyncedAt;
    public String attributesJson;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
