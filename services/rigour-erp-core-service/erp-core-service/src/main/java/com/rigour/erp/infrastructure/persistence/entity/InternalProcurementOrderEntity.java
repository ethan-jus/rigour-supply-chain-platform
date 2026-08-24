package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** ERP 自研采购订单头实体，对应 `erp_procurement_order`。 */
@TableName("erp_procurement_order")
public class InternalProcurementOrderEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 采购订单号，由 ERP 编码规则生成。 */
    private String procurementNo;
    /** 供应商ID。 */
    private Long supplierId;
    /** 计划入库仓库ID。 */
    private Long targetWarehouseId;
    /** 采购状态，关联 PURCHASE_STATUS 字典项。 */
    private String statusCode;
    /** 预计到货时间。 */
    private LocalDateTime expectedArrivalTime;
    /** 采购总数量。 */
    private BigDecimal totalQuantity;
    /** 采购总金额。 */
    private BigDecimal totalAmount;
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
    public String getProcurementNo() { return procurementNo; }
    public void setProcurementNo(String procurementNo) { this.procurementNo = procurementNo; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public Long getTargetWarehouseId() { return targetWarehouseId; }
    public void setTargetWarehouseId(Long targetWarehouseId) { this.targetWarehouseId = targetWarehouseId; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public LocalDateTime getExpectedArrivalTime() { return expectedArrivalTime; }
    public void setExpectedArrivalTime(LocalDateTime expectedArrivalTime) { this.expectedArrivalTime = expectedArrivalTime; }
    public BigDecimal getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(BigDecimal totalQuantity) { this.totalQuantity = totalQuantity; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
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
