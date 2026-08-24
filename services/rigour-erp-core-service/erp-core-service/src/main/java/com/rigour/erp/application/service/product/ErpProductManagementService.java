package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductImageCommand;
import com.rigour.erp.api.v1.model.ProductManagementCommand;
import com.rigour.erp.api.v1.model.ProductManagementDetailView;
import com.rigour.erp.api.v1.model.ProductManagementSummaryView;
import com.rigour.erp.api.v1.model.ProductVariantCommand;
import com.rigour.erp.application.port.out.ErpProductManagementStore;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductImageWrite;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductSearchCriteria;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductVariantWrite;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductWrite;
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.erp.domain.code.ErpBusinessCodeRules;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** ERP 商品管理用例；保存草稿和提交商品共用同一套业务清洗规则。 */
@Service
public final class ErpProductManagementService {
    private static final Logger log = LoggerFactory.getLogger(ErpProductManagementService.class);
    private static final String READ_PERMISSION = "erp:product:read";
    private static final String WRITE_PERMISSION = "erp:product:write";
    private static final String DRAFT = "DRAFT";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String SPOT = "SPOT";
    private static final String OFF_SHELF = "OFF_SHELF";
    private static final String MAIN_IMAGE = "MAIN";
    private static final String DETAIL_IMAGE = "DETAIL";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ErpProductManagementStore store;
    private final BusinessCodeGenerator codeGenerator;

    @Autowired
    public ErpProductManagementService(ErpProductManagementStore store) {
        this(store, new BusinessCodeGenerator());
    }

    ErpProductManagementService(ErpProductManagementStore store, BusinessCodeGenerator codeGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.codeGenerator = Objects.requireNonNull(codeGenerator, "codeGenerator");
    }

    public MasterDataPageView<ProductManagementSummaryView> products(
            int begin, int step, String productCode, String productName, Long categoryId, Long brandId,
            String unitCode, String saleTypeCode, String shelfStatusCode, String submitStatusCode,
            Long defaultWarehouseId) {
        String tenantId = tenant(READ_PERMISSION);
        ProductSearchCriteria criteria = new ProductSearchCriteria(
                ErpServiceValidation.text(productCode, 50, "productCode"),
                ErpServiceValidation.text(productName, 200, "productName"),
                ErpServiceValidation.optionalId(categoryId, "categoryId"),
                ErpServiceValidation.optionalId(brandId, "brandId"),
                ErpServiceValidation.code(unitCode, "unitCode", false),
                ErpServiceValidation.code(saleTypeCode, "saleTypeCode", false),
                ErpServiceValidation.code(shelfStatusCode, "shelfStatusCode", false),
                ErpServiceValidation.code(submitStatusCode, "submitStatusCode", false),
                ErpServiceValidation.optionalId(defaultWarehouseId, "defaultWarehouseId"));
        MasterDataPageView<ProductManagementSummaryView> result = store.products(
                tenantId, ErpServiceValidation.pageBegin(begin), ErpServiceValidation.pageStep(step), criteria);
        log.debug("ERP商品列表查询完成 tenantId={} productCode={} productName={} categoryId={} brandId={} submitStatusCode={} count={} total={}",
                tenantId, ErpServiceValidation.value(criteria.productCode()),
                ErpServiceValidation.value(criteria.productName()), criteria.categoryId(), criteria.brandId(),
                ErpServiceValidation.value(criteria.submitStatusCode()), result.items().size(), result.total());
        return result;
    }

    public ProductManagementDetailView product(Long id) {
        String tenantId = tenant(READ_PERMISSION);
        ProductManagementDetailView result = store.product(tenantId, ErpServiceValidation.requireId(id, "商品ID无效"))
                .orElseThrow(() -> notFound("商品不存在"));
        log.debug("ERP商品详情查询完成 tenantId={} productId={} productCode={}",
                tenantId, result.id(), result.productCode());
        return result;
    }

    public ProductManagementDetailView create(ProductManagementCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        ProductWrite normalized = normalize(tenantId, null, command, false);
        String productCode = codeGenerator.generateUnique(ErpBusinessCodeRules.PRODUCT,
                candidate -> !store.existsByCode(tenantId, candidate));
        ProductManagementDetailView created = store.create(
                tenantId, productCode, normalized, actor.principalId().toString());
        log.info("ERP商品创建完成 tenantId={} productId={} productCode={} submitStatusCode={} actorId={}",
                tenantId, created.id(), created.productCode(), created.submitStatusCode(), actor.principalId());
        return created;
    }

    public ProductManagementDetailView update(Long id, ProductManagementCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        Long productId = ErpServiceValidation.requireId(id, "商品ID无效");
        String tenantId = actor.tenantId().toString();
        ProductWrite normalized = normalize(tenantId, productId, command, true);
        ProductManagementDetailView updated = store.update(
                tenantId, productId, normalized, actor.principalId().toString());
        log.info("ERP商品修改完成 tenantId={} productId={} productCode={} submitStatusCode={} revision={} actorId={}",
                tenantId, updated.id(), updated.productCode(), updated.submitStatusCode(),
                updated.revision(), actor.principalId());
        return updated;
    }

    public void delete(Long id, int revision) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        ErpServiceValidation.requireRevision(revision);
        Long productId = ErpServiceValidation.requireId(id, "商品ID无效");
        String tenantId = actor.tenantId().toString();
        store.delete(tenantId, productId, revision, actor.principalId().toString());
        log.info("ERP商品逻辑删除完成 tenantId={} productId={} revision={} actorId={}",
                tenantId, productId, revision, actor.principalId());
    }

    private ProductWrite normalize(String tenantId, Long productId, ProductManagementCommand command, boolean update) {
        if (command == null) throw badRequest("商品参数不能为空");
        ErpServiceValidation.checkRevision(command.revision(), update);
        boolean submit = Boolean.TRUE.equals(command.submit());
        String productName = submit
                ? ErpServiceValidation.required(command.productName(), "productName不能为空", 200)
                : ErpServiceValidation.text(command.productName(), 200, "productName");
        Long categoryId = ErpServiceValidation.optionalId(command.categoryId(), "categoryId");
        Long brandId = ErpServiceValidation.optionalId(command.brandId(), "brandId");
        Long warehouseId = ErpServiceValidation.optionalId(command.defaultWarehouseId(), "defaultWarehouseId");
        String unitCode = ErpServiceValidation.code(command.unitCode(), "unitCode", submit);
        boolean orderMultipleFlag = Boolean.TRUE.equals(command.orderMultipleFlag());
        BigDecimal orderMultipleQuantity = orderMultipleFlag
                ? quantity(command.orderMultipleQuantity(), "orderMultipleQuantity", submit)
                : null;
        ProductWrite write = new ProductWrite(productName, categoryId, brandId,
                ErpServiceValidation.text(command.productSpecification(), 500, "productSpecification"),
                unitCode, quantity(command.minOrderQuantity(), "minOrderQuantity", false),
                orderMultipleFlag, orderMultipleQuantity,
                ErpServiceValidation.defaultCode(command.saleTypeCode(), "saleTypeCode", SPOT),
                ErpServiceValidation.defaultCode(command.shelfStatusCode(), "shelfStatusCode", OFF_SHELF),
                tagCodes(command.tagCodes()), quantity(command.limitQuantity(), "limitQuantity", false),
                warehouseId, images(command.images()), variants(tenantId, command.variants(), unitCode, submit),
                recommendProductIds(command.recommendProductIds()),
                submit ? SUBMITTED : DRAFT,
                ErpServiceValidation.text(command.remark(), 1000, "remark"),
                update ? command.revision() : 0);
        validateReferences(tenantId, productId, write, submit);
        return write;
    }

    private void validateReferences(String tenantId, Long productId, ProductWrite write, boolean submit) {
        if (submit && write.categoryId() == null) throw badRequest("categoryId不能为空");
        if (submit && write.brandId() == null) throw badRequest("brandId不能为空");
        if (submit && write.defaultWarehouseId() == null) throw badRequest("defaultWarehouseId不能为空");
        if (write.categoryId() != null && !store.categoryActive(tenantId, write.categoryId())) {
            throw notFound("商品分类不存在或已删除");
        }
        if (write.brandId() != null && !store.brandActive(tenantId, write.brandId())) {
            throw notFound("商品品牌不存在或已删除");
        }
        if (write.defaultWarehouseId() != null && !store.warehouseActive(tenantId, write.defaultWarehouseId())) {
            throw notFound("归属仓库不存在或已删除");
        }
        if (!write.tagCodes().isEmpty()) {
            Set<String> expected = new LinkedHashSet<>(write.tagCodes());
            Set<String> actual = store.activeTagCodes(tenantId, expected);
            if (!actual.containsAll(expected)) throw notFound("商品标签不存在或已删除");
        }
        if (!write.recommendProductIds().isEmpty()) {
            Set<Long> expected = new LinkedHashSet<>(write.recommendProductIds());
            if (productId != null && expected.contains(productId)) {
                throw badRequest("推荐商品不能选择当前商品");
            }
            Set<Long> actual = store.activeProductIds(tenantId, expected);
            if (!actual.containsAll(expected)) throw notFound("推荐商品不存在或已删除");
        }
    }

    private List<ProductImageWrite> images(List<ProductImageCommand> source) {
        if (source == null || source.isEmpty()) return List.of();
        if (source.size() > 24) throw badRequest("商品图片最多上传24张");
        List<ProductImageWrite> result = new ArrayList<>();
        int mainCount = 0;
        for (int i = 0; i < source.size(); i++) {
            ProductImageCommand item = source.get(i);
            if (item == null) continue;
            String key = ErpServiceValidation.required(item.imageKey(), "imageKey不能为空", 512);
            if (key.contains("..") || key.startsWith("/")) throw badRequest("imageKey格式无效");
            String type = ErpServiceValidation.defaultCode(item.imageTypeCode(), "imageTypeCode",
                    i == 0 ? MAIN_IMAGE : DETAIL_IMAGE);
            if (MAIN_IMAGE.equals(type)) mainCount++;
            result.add(new ProductImageWrite(key, type, item.ordinal() == null ? i : item.ordinal()));
        }
        if (mainCount > 1) throw badRequest("商品主图只能设置一张");
        return result;
    }

    private List<ProductVariantWrite> variants(String tenantId, List<ProductVariantCommand> source, String productUnitCode,
                                                boolean submit) {
        if (source == null || source.isEmpty()) {
            if (submit) throw badRequest("提交商品至少需要一个规格价格");
            return List.of();
        }
        List<ProductVariantWrite> result = new ArrayList<>();
        int defaultCount = 0;
        for (int i = 0; i < source.size(); i++) {
            ProductVariantCommand item = source.get(i);
            if (item == null) continue;
            Long id = ErpServiceValidation.optionalId(item.id(), "variantId");
            String unitCode = ErpServiceValidation.code(
                    item.unitCode() == null ? productUnitCode : item.unitCode(), "variantUnitCode", submit);
            boolean defaultFlag = item.defaultFlag() != null ? item.defaultFlag() : i == 0;
            if (defaultFlag) defaultCount++;
            String variantCode = id == null ? codeGenerator.generateUnique(ErpBusinessCodeRules.SKU,
                    candidate -> !store.existsVariantByCode(tenantId, candidate)) : null;
            result.add(new ProductVariantWrite(id, variantCode,
                    ErpServiceValidation.text(item.specificationSnapshot(), 500, "specificationSnapshot"),
                    unitCode, money(item.salePrice(), "salePrice", submit), money(item.marketPrice(), "marketPrice", false),
                    money(item.purchasePrice(), "purchasePrice", false),
                    quantity(item.minOrderQuantity(), "variantMinOrderQuantity", false),
                    quantity(item.orderMultipleQuantity(), "variantOrderMultipleQuantity", false),
                    quantity(item.limitQuantity(), "variantLimitQuantity", false),
                    defaultFlag, ErpServiceValidation.text(item.remark(), 1000, "variantRemark")));
        }
        if (defaultCount != 1) throw badRequest("商品规格必须且只能有一个默认规格");
        return result;
    }

    private static List<String> tagCodes(List<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        if (source.size() > 20) throw badRequest("商品标签最多选择20个");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String item : source) {
            String code = ErpServiceValidation.code(item, "tagCode", false);
            if (code != null) result.add(code);
        }
        return List.copyOf(result);
    }

    private static List<Long> recommendProductIds(List<Long> source) {
        if (source == null || source.isEmpty()) return List.of();
        if (source.size() > 20) throw badRequest("推荐商品最多选择20个");
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (Long id : source) {
            result.add(ErpServiceValidation.requireId(id, "recommendProductId无效"));
        }
        return List.copyOf(result);
    }

    private static BigDecimal quantity(BigDecimal value, String name, boolean required) {
        if (value == null) {
            if (required) throw badRequest(name + "不能为空");
            return null;
        }
        if (value.compareTo(ZERO) <= 0) throw badRequest(name + "必须大于0");
        return value;
    }

    private static BigDecimal money(BigDecimal value, String name, boolean required) {
        if (value == null) {
            if (required) throw badRequest(name + "不能为空");
            return null;
        }
        if (required && value.compareTo(ZERO) <= 0) throw badRequest(name + "必须大于0");
        if (!required && value.compareTo(ZERO) < 0) throw badRequest(name + "不能小于0");
        return value;
    }

    private static CallerIdentity actor(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static String tenant(String permission) {
        return actor(permission).tenantId().toString();
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }
}
