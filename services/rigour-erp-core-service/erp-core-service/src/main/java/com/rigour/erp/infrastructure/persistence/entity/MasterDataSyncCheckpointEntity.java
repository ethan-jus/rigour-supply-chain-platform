package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 商品主数据来源增量游标实体；仅在完整批次成功后推进。 */
@TableName("erp_master_data_sync_checkpoint")
public class MasterDataSyncCheckpointEntity {
    /** 同步游标主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** Integration 订货宝连接器 UUID。 */
    public String connectorId;
    /** 来源系统编码。 */
    public String sourceSystem;
    /** 同步对象类型。 */
    public String objectType;
    /** 游标类型，例如 PAGE_TOKEN 或 TIME_WINDOW。 */
    public String cursorType;
    /** 来源分页令牌或其他游标值。 */
    public String cursorValue;
    /** 已确认成功处理的来源更新时间，UTC。 */
    public LocalDateTime sourceUpdatedAt;
    /** 最近一次完整成功批次 UUID。 */
    public String lastSuccessRunId;
    /** 乐观版本号。 */
    public Long version;
    /** 游标记录创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** 游标记录最近更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
