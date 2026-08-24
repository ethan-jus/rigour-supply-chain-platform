package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 自研入库单头实体，对应 `erp_stock_in_order`。 */
@TableName("erp_stock_in_order")
public class InternalStockInOrderEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 入库单号，由 ERP 编码规则生成。 */
    private String stockInNo;
    /** 入库类型，关联 STOCK_IN_TYPE 字典项。 */
    private String stockInTypeCode;
    /** 采购订单ID；采购入库时必填。 */
    private Long procurementOrderId;
    /** 采购订单号快照。 */
    private String procurementNo;
    /** 调拨单ID；调拨入库时用于追溯来源调拨单。 */
    private Long transferOrderId;
    /** 调拨单号快照。 */
    private String transferOrderNo;
    /** 入库仓库ID。 */
    private Long warehouseId;
    /** 供应商ID。 */
    private Long supplierId;
    /** 入库状态，关联 STOCK_IN_STATUS 字典项。 */
    private String statusCode;
    /** 确认入库时间。 */
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
    public String getStockInNo() { return stockInNo; }
    public void setStockInNo(String stockInNo) { this.stockInNo = stockInNo; }
    public String getStockInTypeCode() { return stockInTypeCode; }
    public void setStockInTypeCode(String stockInTypeCode) { this.stockInTypeCode = stockInTypeCode; }
    public Long getProcurementOrderId() { return procurementOrderId; }
    public void setProcurementOrderId(Long procurementOrderId) { this.procurementOrderId = procurementOrderId; }
    public String getProcurementNo() { return procurementNo; }
    public void setProcurementNo(String procurementNo) { this.procurementNo = procurementNo; }
    public Long getTransferOrderId() { return transferOrderId; }
    public void setTransferOrderId(Long transferOrderId) { this.transferOrderId = transferOrderId; }
    public String getTransferOrderNo() { return transferOrderNo; }
    public void setTransferOrderNo(String transferOrderNo) { this.transferOrderNo = transferOrderNo; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
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
