package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 自研调拨单头实体，对应 `erp_transfer_order`。 */
@TableName("erp_transfer_order")
public class InternalTransferOrderEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 调拨单号，由 ERP 编码规则生成。 */
    private String transferNo;
    /** 来源仓库ID。 */
    private Long sourceWarehouseId;
    /** 目标仓库ID。 */
    private Long targetWarehouseId;
    /** 调拨单状态，关联 TRANSFER_STATUS 字典项。 */
    private String statusCode;
    /** 确认调拨出库时间。 */
    private LocalDateTime stockOutTime;
    /** 确认调拨入库时间。 */
    private LocalDateTime stockInTime;
    /** 备注。 */
    private String remark;
    /** 乐观锁。 */
    private Integer revision;
    /** 创建人。 */
    private String createdBy;
    /** 创建时间。 */
    private LocalDateTime createdTime;
    /** 更新人。 */
    private String updatedBy;
    /** 更新时间。 */
    private LocalDateTime updatedTime;
    /** 删除标识：0未删除，1已删除。 */
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTransferNo() { return transferNo; }
    public void setTransferNo(String transferNo) { this.transferNo = transferNo; }
    public Long getSourceWarehouseId() { return sourceWarehouseId; }
    public void setSourceWarehouseId(Long sourceWarehouseId) { this.sourceWarehouseId = sourceWarehouseId; }
    public Long getTargetWarehouseId() { return targetWarehouseId; }
    public void setTargetWarehouseId(Long targetWarehouseId) { this.targetWarehouseId = targetWarehouseId; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public LocalDateTime getStockOutTime() { return stockOutTime; }
    public void setStockOutTime(LocalDateTime stockOutTime) { this.stockOutTime = stockOutTime; }
    public LocalDateTime getStockInTime() { return stockInTime; }
    public void setStockInTime(LocalDateTime stockInTime) { this.stockInTime = stockInTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
