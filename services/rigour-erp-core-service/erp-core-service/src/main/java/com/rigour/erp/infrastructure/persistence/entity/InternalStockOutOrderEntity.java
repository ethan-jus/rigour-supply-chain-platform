package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 统一出库单头实体，对应 `erp_stock_out_order`，销售/调拨通过 stockOutTypeCode 区分。 */
@TableName("erp_stock_out_order")
public class InternalStockOutOrderEntity {
    /** ID。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 租户ID。 */
    private String tenantId;
    /** 出库单号，由 ERP 编码规则生成。 */
    private String stockOutNo;
    /** 出库类型，关联 STOCK_OUT_TYPE 字典项。 */
    private String stockOutTypeCode;
    /** 出库仓库ID。 */
    private Long warehouseId;
    /** 销售订单ID，跨 Order 服务引用。 */
    private Long salesOrderId;
    /** 销售订单号快照。 */
    private String salesOrderNo;
    /** 调拨单ID。 */
    private Long transferOrderId;
    /** 调拨单号快照。 */
    private String transferOrderNo;
    /** 客户ID，跨 CRM 服务引用。 */
    private Long customerId;
    /** 客户名称快照。 */
    private String customerNameSnapshot;
    /** 出库单状态，关联 STOCK_OUT_STATUS 字典项。 */
    private String statusCode;
    /** 确认出库时间。 */
    private LocalDateTime stockOutTime;
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
    public String getStockOutNo() { return stockOutNo; }
    public void setStockOutNo(String stockOutNo) { this.stockOutNo = stockOutNo; }
    public String getStockOutTypeCode() { return stockOutTypeCode; }
    public void setStockOutTypeCode(String stockOutTypeCode) { this.stockOutTypeCode = stockOutTypeCode; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public Long getSalesOrderId() { return salesOrderId; }
    public void setSalesOrderId(Long salesOrderId) { this.salesOrderId = salesOrderId; }
    public String getSalesOrderNo() { return salesOrderNo; }
    public void setSalesOrderNo(String salesOrderNo) { this.salesOrderNo = salesOrderNo; }
    public Long getTransferOrderId() { return transferOrderId; }
    public void setTransferOrderId(Long transferOrderId) { this.transferOrderId = transferOrderId; }
    public String getTransferOrderNo() { return transferOrderNo; }
    public void setTransferOrderNo(String transferOrderNo) { this.transferOrderNo = transferOrderNo; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCustomerNameSnapshot() { return customerNameSnapshot; }
    public void setCustomerNameSnapshot(String customerNameSnapshot) { this.customerNameSnapshot = customerNameSnapshot; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public LocalDateTime getStockOutTime() { return stockOutTime; }
    public void setStockOutTime(LocalDateTime stockOutTime) { this.stockOutTime = stockOutTime; }
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
