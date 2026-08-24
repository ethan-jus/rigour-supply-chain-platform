package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductManagementDetailView;
import com.rigour.erp.api.v1.model.ProductManagementSummaryView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** ERP 自研商品管理持久化端口；只操作我方商品、规格和引用资料。 */
public interface ErpProductManagementStore {
    MasterDataPageView<ProductManagementSummaryView> products(
            String tenantId, int begin, int step, ProductSearchCriteria criteria);

    Optional<ProductManagementDetailView> product(String tenantId, Long id);

    boolean existsByCode(String tenantId, String productCode);

    boolean existsVariantByCode(String tenantId, String variantCode);

    boolean categoryActive(String tenantId, Long categoryId);

    boolean brandActive(String tenantId, Long brandId);

    boolean warehouseActive(String tenantId, Long warehouseId);

    Set<String> activeTagCodes(String tenantId, Set<String> tagCodes);

    Set<Long> activeProductIds(String tenantId, Set<Long> productIds);

    ProductManagementDetailView create(String tenantId, String productCode, ProductWrite command, String actorId);

    ProductManagementDetailView update(String tenantId, Long id, ProductWrite command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    /** 商品列表独立筛选条件；不使用 keyword 汇总多个业务字段。 */
    record ProductSearchCriteria(
            String productCode,
            String productName,
            Long categoryId,
            Long brandId,
            String unitCode,
            String saleTypeCode,
            String shelfStatusCode,
            String submitStatusCode,
            Long defaultWarehouseId) {
    }

    /** 商品聚合写入模型；Service 已完成入参清洗、默认值和提交校验。 */
    record ProductWrite(
            String productName,
            Long categoryId,
            Long brandId,
            String productSpecification,
            String unitCode,
            BigDecimal minOrderQuantity,
            Boolean orderMultipleFlag,
            BigDecimal orderMultipleQuantity,
            String saleTypeCode,
            String shelfStatusCode,
            List<String> tagCodes,
            BigDecimal limitQuantity,
            Long defaultWarehouseId,
            List<ProductImageWrite> images,
            List<ProductVariantWrite> variants,
            List<Long> recommendProductIds,
            String submitStatusCode,
            String remark,
            Integer revision) {
        public ProductWrite {
            tagCodes = tagCodes == null ? List.of() : List.copyOf(tagCodes);
            images = images == null ? List.of() : List.copyOf(images);
            variants = variants == null ? List.of() : List.copyOf(variants);
            recommendProductIds = recommendProductIds == null ? List.of() : List.copyOf(recommendProductIds);
        }
    }

    /** 商品图片写入模型；只保存 COS object key 和展示类型。 */
    record ProductImageWrite(String imageKey, String imageTypeCode, Integer ordinal) {
    }

    /** 商品规格价格写入模型；variantCode 仅新增规格时生成。 */
    record ProductVariantWrite(
            Long id,
            String variantCode,
            String specificationSnapshot,
            String unitCode,
            BigDecimal salePrice,
            BigDecimal marketPrice,
            BigDecimal purchasePrice,
            BigDecimal minOrderQuantity,
            BigDecimal orderMultipleQuantity,
            BigDecimal limitQuantity,
            Boolean defaultFlag,
            String remark) {
    }
}
