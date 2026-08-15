package com.rigour.settings.api.v1.model;

/**
 * 字典项自动补齐结果。
 *
 * @param effective 补齐后的有效字典快照
 * @param observed 去重后的来源值数量
 * @param created 本次新增的启用项数量
 * @param existing 已存在且保持不变的启用项数量
 * @param blocked 已存在但已停用、因此未自动重新启用的项数量
 */
public record DictSyncResult(
        EffectiveDictView effective,
        int observed,
        int created,
        int existing,
        int blocked) {
}
