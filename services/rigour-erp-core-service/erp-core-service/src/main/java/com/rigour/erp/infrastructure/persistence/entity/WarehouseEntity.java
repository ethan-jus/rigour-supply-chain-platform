package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 仓库实体。 */
@TableName("erp_warehouse")
public class WarehouseEntity {
    /** ERP 仓库 UUID。 */ @TableId(type = IdType.INPUT) public String id;
    /** 租户键。 */ public String tenantId;
    /** ERP 仓库编码。 */ public String warehouseCode;
    /** 仓库名称。 */ public String name;
    /** 订货宝仓库 ID。 */ public String sourceWarehouseId;
    /** 订货宝仓库 Guid。 */ public String sourceWarehouseGuid;
    /** 订货宝仓库状态。 */ public String sourceStatus;
    /** 订货宝默认仓标记。 */ public Boolean sourceDefaultFlag;
    /** 仓库面积。 */ public BigDecimal acreage;
    /** 脱敏电话。 */ public String phoneMasked;
    /** 仓库地址。 */ public String address;
    /** 协作方来源 ID。 */ public String collaboratorSourceId;
    /** 来源备注。 */ public String remark;
    /** ERP 内部状态。 */ public String internalStatus;
    /** 数据主权状态。 */ public String ownershipState;
    /** 记录来源。 */ public String recordOrigin;
    /** 最近成功同步时间。 */ public LocalDateTime sourceSyncedAt;
    /** 扩展字段及摘要 JSON。 */ public String attributesJson;
    /** 乐观锁版本。 */ public Long version;
    /** 创建时间。 */ public LocalDateTime createdAt;
    /** 更新时间。 */ public LocalDateTime updatedAt;
}
