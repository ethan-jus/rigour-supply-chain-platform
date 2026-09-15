package com.rigour.settings.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 保存合并前版本、目标和操作主体，用于追溯历史兼容关系。 */
@Mapper
public interface DictMergeLogMapper {
    @Insert("""
        INSERT INTO data_dictionary_merge_log
        (tenant_id, dictionary_code, source_item_code, target_item_code, source_revision, target_revision, reason, created_by)
        VALUES (#{tenant}, #{dictionary}, #{source}, #{target}, #{sourceRevision}, #{targetRevision}, #{reason}, #{actor})
        """)
    void insert(@Param("tenant") String tenant, @Param("dictionary") String dictionary,
                @Param("source") String source, @Param("target") String target,
                @Param("sourceRevision") int sourceRevision, @Param("targetRevision") int targetRevision,
                @Param("reason") String reason, @Param("actor") String actor);
}
