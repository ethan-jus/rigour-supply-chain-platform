package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 库存余额实体；幂等粒度为仓库、商品及规格组合。 */
@TableName("erp_inventory_balance")
public class InventoryBalanceEntity {
    @TableId(type = IdType.INPUT) public String id;
    public String tenantId;
    public String warehouseId;
    public String spuId;
    public String skuId;
    public String sourceWarehouseKey;
    public String sourceWarehouseGuid;
    public String sourceWarehouseCode;
    public String sourceWarehouseName;
    public String sourceProductKey;
    public String sourceGoodsGuid;
    public String sourceGoodsCode;
    public String sourceGoodsName;
    public String sourceVariantKey;
    public String firstOptionGuid;
    public String firstOptionCode;
    public String firstOptionName;
    public String secondOptionGuid;
    public String secondOptionCode;
    public String secondOptionName;
    public BigDecimal realQuantity;
    public BigDecimal availableQuantity;
    public BigDecimal reservedQuantity;
    public BigDecimal inTransitQuantity;
    public String calculationOrigin;
    public LocalDateTime sourceSyncedAt;
    public String attributesJson;
    public Long version;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
