package com.rigour.settings.api.v1.model;

import java.util.UUID;

/**
 * 新增或修改字典；scopeId 由服务端根据作用域计算，浏览器不能自填。
 *
 * @param code 字典编码，创建后不可修改
 * @param name 字典中文名称
 * @param scopeType SYSTEM/MODULE/TENANT，创建后不可修改
 * @param moduleCode COMMON/ERP/CRM/ORDER等模块编码，创建后不可修改
 * @param tenantId 租户级字典的租户ID；租户身份由服务端强制使用当前租户
 * @param baseDictId 可选基础字典；创建租户字典时复制完整条目树
 * @param status ACTIVE/DISABLED
 * @param sortNo 展示顺序
 * @param remark 维护说明
 * @param version 修改时使用的乐观锁版本，新增必须为0
 */
public record DictCommand(
        String code,
        String name,
        String scopeType,
        String moduleCode,
        String tenantId,
        UUID baseDictId,
        String status,
        int sortNo,
        String remark,
        long version) {
}
