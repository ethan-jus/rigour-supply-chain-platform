package com.rigour.settings.api.v1.model;

/**
 * 来源值解析结果；保留旧统计字段以兼容滚动发布。
 *
 * @param effective 当前字典快照，含历史兼容项供旧版客户端解析
 * @param observed 去重后的来源值数量
 * @param created 保留的兼容字段，新实现恒为零
 * @param existing 已解析到标准项的来源值数量
 * @param blocked 未能解析的来源值数量
 */
public record DictSyncResult(
        EffectiveDictView effective,
        int observed,
        int created,
        int existing,
        int blocked,
        java.util.Map<String, DictValueResolution> resolutions) {
    /** 兼容既有契约调用；新客户端优先读取服务端原值解析结果。 */
    public DictSyncResult(EffectiveDictView effective, int observed, int created, int existing, int blocked) {
        this(effective, observed, created, existing, blocked, null);
    }
}
