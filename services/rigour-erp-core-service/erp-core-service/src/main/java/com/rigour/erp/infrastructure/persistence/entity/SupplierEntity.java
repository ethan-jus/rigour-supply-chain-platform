package com.rigour.erp.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** ERP 供应商实体；地址、联系方式、税号和银行账号按当前内部使用要求保存完整值。 */
@TableName("erp_supplier")
public class SupplierEntity {
    /** ERP 供应商 UUID。 */ @TableId(type = IdType.INPUT) public String id;
    /** 租户键。 */ public String tenantId;
    /** ERP 供应商编码。 */ public String supplierCode;
    /** 供应商名称。 */ public String name;
    /** 订货宝供应商 ID。 */ public String sourceSupplierId;
    /** 订货宝供应商 Guid。 */ public String sourceSupplierGuid;
    /** 地区名称。 */ public String areaName;
    /** 完整地址。 */ public String address;
    /** 联系人名称。 */ public String contactName;
    /** 完整手机。 */ public String mobile;
    /** 完整电话。 */ public String phone;
    /** 完整邮箱。 */ public String email;
    /** 开户名称。 */ public String accountName;
    /** 开户银行。 */ public String bankName;
    /** 完整银行账号。 */ public String bankAccount;
    /** 发票抬头。 */ public String invoiceTitle;
    /** 完整纳税人识别号。 */ public String taxpayerNumber;
    /** 来源备注。 */ public String remark;
    /** ERP 内部状态。 */ public String internalStatus;
    /** 数据主权状态。 */ public String ownershipState;
    /** 记录来源。 */ public String recordOrigin;
    /** 来源更新时间。 */ public LocalDateTime sourceUpdatedAt;
    /** 最近成功同步时间。 */ public LocalDateTime sourceSyncedAt;
    /** 扩展字段及摘要 JSON。 */ public String attributesJson;
    /** 乐观锁版本。 */ public Long version;
    /** 创建时间。 */ public LocalDateTime createdAt;
    /** 更新时间。 */ public LocalDateTime updatedAt;
}
