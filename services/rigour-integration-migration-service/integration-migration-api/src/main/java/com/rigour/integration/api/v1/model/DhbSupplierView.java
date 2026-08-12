package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.Map;

/** Integration 归一化供应商；供应商地址、联系方式、税号和银行账号按当前同步要求保留完整值。 */
public record DhbSupplierView(
        /** 订货宝供应商主键。 */ String sourceId,
        /** 订货宝供应商 GUID。 */ String sourceGuid,
        /** 供应商编码。 */ String code,
        /** 供应商名称。 */ String name,
        /** 所在地区名称。 */ String areaName,
        /** 完整地址。 */ String address,
        /** 联系人名称。 */ String contactName,
        /** 完整手机号。 */ String mobile,
        /** 完整固定电话。 */ String phone,
        /** 完整邮箱。 */ String email,
        /** 开户名称。 */ String accountName,
        /** 开户行名称。 */ String bankName,
        /** 完整银行账号。 */ String bankAccount,
        /** 发票抬头。 */ String invoiceTitle,
        /** 完整纳税人识别号。 */ String taxpayerNumber,
        /** 来源备注。 */ String remark,
        /** 来源更新时间。 */ Instant sourceUpdatedAt,
        /** 未归一化的扩展来源字段。 */ Map<String, Object> sourceFields) { }
