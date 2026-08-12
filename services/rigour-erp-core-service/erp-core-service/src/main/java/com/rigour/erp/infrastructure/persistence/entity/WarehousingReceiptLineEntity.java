package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 入库单明细实体。 */
@TableName("erp_warehousing_receipt_line")
public class WarehousingReceiptLineEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String tenantId;
    public String warehousingReceiptId;
    public String sourceLineId;
    public String spuId;
    public String skuId;
    public String sourceGoodsId;
    public String sourceGoodsCode;
    public String sourceGoodsName;
    public String sourceOptionsId;
    public String sourceOptionsGoodsCode;
    public String optionsSummary;
    public BigDecimal baseQuantity;
    public BigDecimal unitQuantity;
    public String unitCode;
    public String unitName;
    public BigDecimal conversionNumber;
    public BigDecimal costPrice;
    public BigDecimal unitCostPrice;
    public BigDecimal purchasePrice;
    public BigDecimal wholesalePrice;
    public String allocation;
    public String barcode;
    public String goodsModel;
    public BigDecimal sourceRealQuantity;
    public BigDecimal sourceAvailableQuantity;
    public String collaboratorSourceId;
    public String collaboratorName;
    public String remark;
    public String attributesJson;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
