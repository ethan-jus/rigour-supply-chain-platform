package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 采购退货明细实体。 */
@TableName("erp_purchase_return_line")
public class PurchaseReturnLineEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String tenantId;
    public String purchaseReturnId;
    public String sourceLineId;
    public String spuId;
    public String skuId;
    public String purchaseOrderLineId;
    public String sourceGoodsId;
    public String sourceGoodsCode;
    public String sourceGoodsName;
    public String sourceOptionsId;
    public String sourceOptionsGoodsCode;
    public String optionsSummary;
    public BigDecimal requestedQuantity;
    public BigDecimal confirmedQuantity;
    public BigDecimal returnPrice;
    public BigDecimal confirmedPrice;
    public String returnUnitCode;
    public String returnUnitName;
    public BigDecimal returnUnitQuantity;
    public BigDecimal confirmedUnitQuantity;
    public BigDecimal conversionNumber;
    public BigDecimal amount;
    public BigDecimal costPrice;
    public String purchaseOrderNo;
    public String categoryNameSnapshot;
    public String brandNameSnapshot;
    public String remark;
    public String attributesJson;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
