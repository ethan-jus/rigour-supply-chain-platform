package com.rigour.settings.api.v1.model;

import java.util.UUID;

/**
 * 字典项；parentId 和 levelNo 由服务端校验并维护。
 *
 * @param id 字典项主键
 * @param dictId 所属字典主键
 * @param parentId 父字典项主键，根节点为空
 * @param levelNo 层级，根节点为1
 * @param code 字典项业务编码
 * @param name 面向业务人员的显示名称
 * @param value 可选业务值
 * @param sortNo 同级展示顺序
 * @param status ACTIVE/DISABLED
 * @param extraJson 颜色、图标、精度等非核心展示扩展
 * @param version 乐观锁版本
 */
public record DictItemView(
        UUID id,
        UUID dictId,
        UUID parentId,
        int levelNo,
        String code,
        String name,
        String value,
        int sortNo,
        String status,
        String extraJson,
        long version) {
}
