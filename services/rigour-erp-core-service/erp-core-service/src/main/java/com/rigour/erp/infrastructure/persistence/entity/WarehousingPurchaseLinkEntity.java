package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 入库单与采购单关系实体。 */
@TableName("erp_warehousing_purchase_link")
public class WarehousingPurchaseLinkEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String tenantId;
    public String warehousingReceiptId;
    public String purchaseOrderId;
    public String sourcePurchaseId;
    public String purchaseOrderNo;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
