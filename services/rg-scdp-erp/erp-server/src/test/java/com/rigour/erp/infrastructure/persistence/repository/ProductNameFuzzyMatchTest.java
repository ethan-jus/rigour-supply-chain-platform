package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ProductNameFuzzyMatchTest {

    @Test
    void matchesFeishuBrandProductSpecDescriptorByProductName() throws Exception {
        assertThat(fuzzyProductNameMatches("油泼辣子拌面", "杨掌柜-油泼辣子拌面-12桶/箱"))
                .isTrue();
        assertThat(fuzzyProductNameMatches("油泼辣子拌面", "SP2728 - 杨掌柜-油泼辣子拌面-12桶/箱"))
                .isTrue();
        assertThat(fuzzyProductNameMatches("澳洋台呢底布(华彩中式 )", "澳洋-华彩（中式）底布-A500B"))
                .isTrue();
        assertThat(fuzzyProductNameMatches("澳洋台呢 (华彩 中式)", "澳洋-华彩（中式）套装-A600"))
                .isTrue();
        assertThat(fuzzyProductNameMatches("澳洋斯诺克台呢", "澳洋-斯诺克套装-极光"))
                .isTrue();
        assertThat(fuzzyProductNameMatches("启航款皮头", "勇者-CBSA皮头-赠品(启航款)-H/M  12个/包"))
                .isTrue();
        assertThat(fuzzyProductNameMatches("粉面菜蛋金汤肥牛味", "杨掌柜-金汤肥牛-12桶/箱"))
                .isTrue();
    }

    @Test
    void matchesFeishuVariantDescriptorByClosestSpecification() throws Exception {
        assertThat(fuzzySpecificationMatches("A500", "A500B")).isTrue();
        assertThat(fuzzySpecificationMatches("极光Aura", "极光")).isTrue();
    }

    @Test
    void allowsFirstVariantFallbackOnlyForFuzzyProductNameStrategy() throws Exception {
        assertThat(allowNameOnlyVariantFallback("PRODUCT_NAME_FUZZY_UNIQUE")).isTrue();
        assertThat(allowNameOnlyVariantFallback("PRODUCT_NAME_EXACT")).isFalse();
    }

    private static boolean fuzzyProductNameMatches(String productName, String sourceName) throws Exception {
        Method method = MybatisPlusProductManagementRepository.class
                .getDeclaredMethod("fuzzyProductNameMatches", String.class, String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, productName, sourceName);
    }

    private static boolean allowNameOnlyVariantFallback(String strategy) throws Exception {
        Method method = MybatisPlusProductManagementRepository.class
                .getDeclaredMethod("allowNameOnlyVariantFallback", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, strategy);
    }

    private static boolean fuzzySpecificationMatches(String specification, String sourceSpecification)
            throws Exception {
        Method method = MybatisPlusProductManagementRepository.class
                .getDeclaredMethod("fuzzySpecificationMatches", String.class, String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, specification, sourceSpecification);
    }
}
