package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 采购退货单头实体。 */
@TableName("erp_purchase_return")
public class PurchaseReturnEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String tenantId;
    public String purchaseReturnNo;
    public String sourceReturnId;
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
    public String internalStatus;
    public BigDecimal returnAmount;
    public BigDecimal discountAmount;
    public String returnReason;
    public LocalDateTime sourceCreatedAt;
    public LocalDateTime returnSendAt;
    public String internalCommunication;
    public String remark;
    public Integer detailCount;
    public String contactName;
    public String contactPhone;
    public String contactAddress;
    public String cityIdsJson;
    public String cityNamesJson;
    public String sourceDevice;
    public String parentReturnSourceId;
    public String parentCompanySourceId;
    public Boolean sourceDownloaded;
    public String ownershipState;
    public String recordOrigin;
    public LocalDateTime sourceSyncedAt;
    public String attributesJson;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
