package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 规范主数据与外部来源对象的一对一幂等绑定实体。 */
@TableName("erp_master_source_binding")
public class MasterSourceBindingEntity {
    /** 来源绑定主键，UUID 字符串。 */
    @TableId(type = IdType.INPUT) public String id;
    /** 租户主键。 */
    public String tenantId;
    /** 来源系统编码，例如 DINGHUOBAO。 */
    public String sourceSystem;
    /** 来源对象类型，例如 PRODUCT_SPU、PRODUCT_SKU 或 BRAND。 */
    public String sourceObjectType;
    /** 来源对象唯一标识。 */
    public String sourceObjectId;
    /** ERP 目标类型，例如 SPU、SKU、CATEGORY 或 BRAND。 */
    public String targetType;
    /** ERP 目标记录 UUID；由 targetType 决定对应目标表。 */
    public String targetId;
    /** 来源编码快照。 */
    public String sourceCode;
    /** 来源名称快照。 */
    public String sourceName;
    /** 来源状态原值。 */
    public String sourceStatus;
    /** 订货宝上下架状态原值。 */
    public String sourcePutaway;
    /** 来源系统更新时间，UTC；接口未提供时为空。 */
    public LocalDateTime sourceUpdatedAt;
    /** 归一化来源字段 SHA-256，用于幂等和变更检测。 */
    public String sourcePayloadHash;
    /** 完整快照中的来源存在状态：PRESENT 或 SOURCE_ABSENT。 */
    public String sourcePresence;
    /** 首次在完整成功快照中未出现的时间。 */
    public LocalDateTime sourceAbsentAt;
    /** 最近处理该绑定的 ERP 同步批次 UUID。 */
    public String lastSyncRunId;
    /** 最近一次成功处理来源对象的时间，UTC。 */
    public LocalDateTime syncedAt;
    /** 乐观版本号。 */
    public Long version;
    /** 来源绑定创建时间，UTC。 */
    public LocalDateTime createdAt;
    /** 来源绑定最近更新时间，UTC。 */
    public LocalDateTime updatedAt;
}
