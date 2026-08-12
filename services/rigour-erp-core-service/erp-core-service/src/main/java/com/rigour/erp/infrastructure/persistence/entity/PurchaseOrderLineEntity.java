package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 采购单明细实体。 */
@TableName("erp_purchase_order_line")
public class PurchaseOrderLineEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String tenantId;
    public String purchaseOrderId;
    public String sourceLineId;
    public String spuId;
    public String skuId;
    public String sourceGoodsId;
    public String sourceGoodsGuid;
    public String sourceGoodsCode;
    public String sourceGoodsName;
    public String sourceOptionsId;
    public String sourceOptionsGoodsCode;
    public String optionsSummary;
    public BigDecimal baseQuantity;
    public BigDecimal unitPrice;
    public String purchaseUnitCode;
    public String purchaseUnitName;
    public BigDecimal purchaseUnitQuantity;
    public BigDecimal warehousedQuantity;
    public BigDecimal returnedQuantity;
    public String remark;
    public String attributesJson;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
