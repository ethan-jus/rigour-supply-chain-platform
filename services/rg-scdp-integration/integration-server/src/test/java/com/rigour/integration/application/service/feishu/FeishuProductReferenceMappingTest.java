package com.rigour.integration.application.service.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.erp.api.v1.model.ExternalProductResolvedView;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeishuProductReferenceMappingTest {
    @Test
    void aSharedBrandOrSpecificationCannotMapChalkToATip() {
        var context = new FeishuImportBundleService.SalesOrderMappingContext(Map.of());
        context.addResolvedProduct(row("瑞盖-专业款皮头-H/M"), resolved(1, 11, "专业款皮头", "H/M"));
        context.addResolvedProduct(row("其他品牌-其他商品-竞技型"), resolved(2, 22, "其他商品", "竞技型"));

        assertThat(context.product("瑞盖-学院竞技巧可粉-竞技型", "学院竞技巧可粉", "竞技型")).isEmpty();
        assertThat(context.product("瑞盖", "H/M")).isEmpty();
        assertThat(context.product("瑞盖-专业款皮头-H/M")).get()
                .extracting(FeishuSalesOrderImportMapper.ProductMapping::productVariantId).isEqualTo(11L);
        var plan = new FeishuSalesOrderImportMapper().linePlan(row("瑞盖-学院竞技巧可粉-竞技型"), context);
        assertThat(plan.command()).isNotNull();
        assertThat(plan.command().productId()).isNull();
        assertThat(plan.command().productVariantId()).isNull();
        assertThat(plan.command().productNameSnapshot()).contains("巧可粉");
    }

    @Test
    void sharedProductNamesDoNotChooseTheFirstHardnessSku() {
        var context = new FeishuImportBundleService.SalesOrderMappingContext(Map.of());
        context.addResolvedProduct(row("瑞盖-专业款皮头-H"), resolved(1, 11, "专业款皮头", "H"));
        context.addResolvedProduct(row("瑞盖-专业款皮头-M"), resolved(1, 12, "专业款皮头", "M"));

        assertThat(context.product("专业款皮头", "H/M")).isEmpty();
        assertThat(context.product("P1")).isEmpty();
        assertThat(context.product("瑞盖-专业款皮头-H")).get()
                .extracting(FeishuSalesOrderImportMapper.ProductMapping::productVariantId).isEqualTo(11L);
        assertThat(context.product(" S12 ")).get()
                .extracting(FeishuSalesOrderImportMapper.ProductMapping::productVariantId).isEqualTo(12L);
    }

    @Test
    void differentClothModelsAndUnknownCompoundNamesAreNotGuessed() {
        var context = new FeishuImportBundleService.SalesOrderMappingContext(Map.of());
        context.addResolvedProduct(row("澳洋-华彩中式底布-A500"), resolved(3, 31, "华彩中式底布", "A500"));

        assertThat(context.product("澳洋-华彩中式底布-A500B", "A500B")).isEmpty();
        assertThat(context.product("澳洋", "A500")).isEmpty();
        assertThat(context.product("专业款皮头,巧克粉")).isEmpty();
    }

    @Test
    void repeatedReferencesToTheSameSkuRemainValid() {
        var context = new FeishuImportBundleService.SalesOrderMappingContext(Map.of());
        context.addResolvedProduct(row("瑞盖-专业款皮头-H"), resolved(1, 11, "专业款皮头", "H"));
        context.addResolvedProduct(row("专业款皮头H"), resolved(1, 11, "专业款皮头", "H"));

        assertThat(context.product("专业款皮头")).get()
                .extracting(FeishuSalesOrderImportMapper.ProductMapping::productVariantId).isEqualTo(11L);
        assertThat(context.product("专业款皮头H")).isPresent();
    }

    private static StoredRawRow row(String productReference) {
        return new StoredRawRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "订单明细", "FEISHU_SALES_ORDER_LINE", "ORDER", "SALES_ORDER_LINE", 1, "LINE-001",
                Instant.parse("2026-09-01T00:00:00Z"), "PENDING",
                Map.of("关联订单", "SO-001", "产品编号", productReference, "数量", "2", "实际小计", "82"), Map.of());
    }

    private static ExternalProductResolvedView resolved(long productId, long variantId, String name, String specification) {
        return new ExternalProductResolvedView("reference", productId, variantId, "P" + productId, "S" + variantId,
                name, specification, "PIECE", "DINGHUOBAO", "PRODUCT_CODE_EXACT", 100, "MATCHED", "matched");
    }
}
