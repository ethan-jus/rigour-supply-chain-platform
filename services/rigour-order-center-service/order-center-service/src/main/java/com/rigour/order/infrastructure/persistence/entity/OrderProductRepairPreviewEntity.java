package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 不可变预览载荷及一次应用结果；租户和操作员均属于幂等边界。 */
@TableName("order_product_repair_preview")
public class OrderProductRepairPreviewEntity {
    @TableId(type = IdType.INPUT)
    public String previewId;
    public String tenantId;
    public Long orderId;
    public String actorId;
    public String status;
    public String fingerprint;
    public String previewJson;
    public String appliedJson;
    public LocalDateTime createdAt;
    public LocalDateTime expiresAt;
    public LocalDateTime appliedAt;
}
