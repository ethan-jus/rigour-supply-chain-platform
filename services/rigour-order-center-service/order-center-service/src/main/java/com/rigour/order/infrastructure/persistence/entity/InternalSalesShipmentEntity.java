package com.rigour.order.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 自研销售发货单实体，对应 `order_sales_shipment`。 */
@TableName("order_sales_shipment")
public class InternalSalesShipmentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String shipmentNo;
    private Long salesOrderId;
    private String salesOrderNoSnapshot;
    private Long customerId;
    private String customerCodeSnapshot;
    private String customerNameSnapshot;
    private String contactPhoneSnapshot;
    private String regionCode;
    private String ownerStaffCode;
    private Long warehouseId;
    private Long stockOutOrderId;
    private String stockOutNo;
    private String shipmentStatusCode;
    private String logisticsCompany;
    private String trackingNo;
    private LocalDateTime shipTime;
    private BigDecimal totalQuantity;
    private String remark;
    private Integer revision;
    private String createdBy;
    private LocalDateTime createdTime;
    private String updatedBy;
    private LocalDateTime updatedTime;
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getShipmentNo() { return shipmentNo; }
    public void setShipmentNo(String shipmentNo) { this.shipmentNo = shipmentNo; }
    public Long getSalesOrderId() { return salesOrderId; }
    public void setSalesOrderId(Long salesOrderId) { this.salesOrderId = salesOrderId; }
    public String getSalesOrderNoSnapshot() { return salesOrderNoSnapshot; }
    public void setSalesOrderNoSnapshot(String salesOrderNoSnapshot) { this.salesOrderNoSnapshot = salesOrderNoSnapshot; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCustomerCodeSnapshot() { return customerCodeSnapshot; }
    public void setCustomerCodeSnapshot(String customerCodeSnapshot) { this.customerCodeSnapshot = customerCodeSnapshot; }
    public String getCustomerNameSnapshot() { return customerNameSnapshot; }
    public void setCustomerNameSnapshot(String customerNameSnapshot) { this.customerNameSnapshot = customerNameSnapshot; }
    public String getContactPhoneSnapshot() { return contactPhoneSnapshot; }
    public void setContactPhoneSnapshot(String contactPhoneSnapshot) { this.contactPhoneSnapshot = contactPhoneSnapshot; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getOwnerStaffCode() { return ownerStaffCode; }
    public void setOwnerStaffCode(String ownerStaffCode) { this.ownerStaffCode = ownerStaffCode; }
    public Long getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Long warehouseId) { this.warehouseId = warehouseId; }
    public Long getStockOutOrderId() { return stockOutOrderId; }
    public void setStockOutOrderId(Long stockOutOrderId) { this.stockOutOrderId = stockOutOrderId; }
    public String getStockOutNo() { return stockOutNo; }
    public void setStockOutNo(String stockOutNo) { this.stockOutNo = stockOutNo; }
    public String getShipmentStatusCode() { return shipmentStatusCode; }
    public void setShipmentStatusCode(String shipmentStatusCode) { this.shipmentStatusCode = shipmentStatusCode; }
    public String getLogisticsCompany() { return logisticsCompany; }
    public void setLogisticsCompany(String logisticsCompany) { this.logisticsCompany = logisticsCompany; }
    public String getTrackingNo() { return trackingNo; }
    public void setTrackingNo(String trackingNo) { this.trackingNo = trackingNo; }
    public LocalDateTime getShipTime() { return shipTime; }
    public void setShipTime(LocalDateTime shipTime) { this.shipTime = shipTime; }
    public BigDecimal getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(BigDecimal totalQuantity) { this.totalQuantity = totalQuantity; }
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
