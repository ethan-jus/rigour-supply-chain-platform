package com.rigour.settings.api.v1.model;

import java.util.UUID;

/**
 * 字典定义；作用域与条目层级相互独立。
 *
 * @param id 字典主键
 * @param code 字典编码
 * @param name 字典中文名称
 * @param scopeType 作用域类型：SYSTEM/MODULE/TENANT
 * @param scopeId 服务端计算的作用域标识
 * @param moduleCode 业务模块编码
 * @param tenantId 租户级字典所属租户
 * @param baseDictId 租户字典复制来源
 * @param status 治理状态：ACTIVE/DISABLED
 * @param sortNo 展示顺序
 * @param remark 维护说明
 * @param version 乐观锁版本
 * @param revision 整本字典内容版本；字典或任一字典项变化时递增
 */
public record DictView(
        UUID id,
        String code,
        String name,
        String scopeType,
        String scopeId,
        String moduleCode,
        String tenantId,
        UUID baseDictId,
        String status,
        int sortNo,
        String remark,
        long version,
        long revision) {
}
