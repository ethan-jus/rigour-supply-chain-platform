package com.rigour.settings.api.v1.model;

import java.util.UUID;

/**
 * 新增或修改字典项；levelNo 不由调用方传入。
 *
 * @param dictId 所属字典主键
 * @param parentId 父字典项主键，根节点为空
 * @param code 字典项业务编码
 * @param name 面向业务人员的显示名称
 * @param value 可选业务值
 * @param sortNo 同级展示顺序
 * @param status ACTIVE/DISABLED
 * @param extraJson 合法JSON格式的非核心展示扩展
 * @param version 修改时使用的乐观锁版本，新增必须为0
 */
public record DictItemCommand(
        UUID dictId,
        UUID parentId,
        String code,
        String name,
        String value,
        int sortNo,
        String status,
        String extraJson,
        long version) {
}
