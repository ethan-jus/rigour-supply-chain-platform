package com.rigour.settings.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.settings.infrastructure.persistence.entity.DictItemEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 字典项表 MyBatis-Plus Mapper。 */
public interface DictItemMapper extends BaseMapper<DictItemEntity> {
    @Insert("""
            <script>
            INSERT INTO data_dictionary_item
                (dictionary_code, dictionary_item_level, parent_dictionary_item_code,
                 dictionary_item_code, dictionary_item_name, remark, ordinal, revision,
                 created_by, updated_by, created_time, updated_time, deleted)
            VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.dictionaryCode}, #{item.dictionaryItemLevel}, #{item.parentDictionaryItemCode},
                 #{item.dictionaryItemCode}, #{item.dictionaryItemName}, #{item.remark}, #{item.ordinal},
                 #{item.revision}, #{item.createdBy}, #{item.updatedBy},
                 #{item.createdTime}, #{item.updatedTime}, #{item.deleted})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("items") List<DictItemEntity> items);
}
