package com.rigour.erp.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 验证商品主分类字段不会生成 MySQL 关键字别名。 */
class ProductSpuCategoryEntityMappingTest {

    @Test
    void mapsPrimaryFlagWithoutReservedPrimaryAlias() {
        TableInfoHelper.remove(ProductSpuCategoryEntity.class);
        try {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                    new Configuration(), "ProductSpuCategoryEntityMappingTest");
            assistant.setCurrentNamespace("com.rigour.erp.infrastructure.persistence.mapper"
                    + ".ProductSpuCategoryMapper");

            TableInfo tableInfo = TableInfoHelper.initTableInfo(
                    assistant, ProductSpuCategoryEntity.class);

            assertThat(tableInfo.getAllSqlSelect())
                    .contains("is_primary AS primaryFlag")
                    .doesNotContain("is_primary AS primary,");
        } finally {
            TableInfoHelper.remove(ProductSpuCategoryEntity.class);
        }
    }
}
