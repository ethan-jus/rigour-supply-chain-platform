package com.rigour.erp.application.model;

import java.util.List;
import java.util.Map;

/**
 * 单次 ERP 同步使用的字典快照与未映射汇总。
 *
 * <p>只记录有限枚举原值和出现次数，不保存订货宝完整报文；原始业务数据仍由 Raw 与 ERP 领域表完整保存。</p>
 *
 * @param unmapped 未找到唯一有效字典映射的来源值出现次数
 * @param revisions 字典编码及本批次使用的整本内容版本；-1表示字典不可用或未配置
 * @param issues 按字典、字段和来源值聚合的未映射项
 */
public record DictionaryMappingAudit(
        long unmapped,
        Map<String, Long> revisions,
        List<MappingIssue> issues) {

    public DictionaryMappingAudit {
        if (unmapped < 0) throw new IllegalArgumentException("unmapped不能小于0");
        revisions = revisions == null ? Map.of() : Map.copyOf(revisions);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static DictionaryMappingAudit empty() {
        return new DictionaryMappingAudit(0, Map.of(), List.of());
    }

    /** 单个未映射来源枚举的聚合记录；不包含凭据或完整业务报文。 */
    public record MappingIssue(String dictCode, String fieldCode, String sourceValue, long count) {
        public MappingIssue {
            if (count < 1) throw new IllegalArgumentException("count必须大于0");
        }
    }
}
