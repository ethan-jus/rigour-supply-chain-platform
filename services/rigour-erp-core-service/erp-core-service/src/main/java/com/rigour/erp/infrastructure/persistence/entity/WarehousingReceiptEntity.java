package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 入库单头实体。 */
@TableName("erp_warehousing_receipt")
public class WarehousingReceiptEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String tenantId;
    public String warehousingNo;
    public String sourceWarehousingId;
    public String sourceWarehouseId;
    public String sourceSupplierId;
    public String warehouseId;
    public String supplierId;
    public String warehouseNameSnapshot;
    public String supplierNameSnapshot;
    public String sourceTypeId;
    public String sourceTypeName;
    public String sourceStatus;
    public String sourceStatusName;
    public String internalStatus;
    public String staffName;
    public String clientSourceId;
    public String accountSourceId;
    public String collaboratorSourceId;
    public String collaboratorName;
    public String logisticsSourceId;
    public String expressNumber;
    public LocalDateTime storageAt;
    public LocalDateTime sourceCreatedAt;
    public LocalDateTime sourceUpdatedAt;
    public BigDecimal freightAmount;
    public BigDecimal totalAmount;
    public BigDecimal costAmount;
    public Boolean sourceApiFlag;
    public String splitType;
    public String remark;
    public String ownershipState;
    public String recordOrigin;
    public LocalDateTime sourceSyncedAt;
    public String attributesJson;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
