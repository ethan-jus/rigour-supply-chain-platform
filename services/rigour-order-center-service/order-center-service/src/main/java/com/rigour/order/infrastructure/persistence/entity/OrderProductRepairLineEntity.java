package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 追加式商品绑定与标准计量证据；原交易计量不参与覆盖，行版本决定证据是否仍有效。 */
@TableName("order_product_repair_line")
public class OrderProductRepairLineEntity {
    @TableField(exist = false)
    public String sourceOrderNo;
    @TableId(type = IdType.AUTO)
    public Long id;
    public String tenantId;
    public Long orderId;
    public Long lineId;
    public String previewId;
    public Integer lineRevision;
    public Long productId;
    public Long productVariantId;
    public String productCode;
    public String skuCode;
    public String storedUnitCode;
    public String historicalTransactionUnitCode;
    public String transactionUnitEvidence;
    public String transactionUnitStatus;
    public BigDecimal transactionQuantity;
    public String standardUnitCode;
    public BigDecimal standardQuantity;
    public BigDecimal conversionFactor;
    public String bindingEvidence;
    public String sourceNamespace;
    public String sourceCaptureRef;
    public String sourceProductRecordId;
    public String sourceProductCode;
    public String sourceEvidence;
    public String sourceIdentityStatus;
    public String conversionEvidence;
    public String originalJson;
    public String appliedJson;
    public String appliedBy;
    public LocalDateTime appliedAt;
}
