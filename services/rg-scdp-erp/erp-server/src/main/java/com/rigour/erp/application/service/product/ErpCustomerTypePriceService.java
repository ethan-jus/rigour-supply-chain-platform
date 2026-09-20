package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.CustomerTypePriceImportCommand;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportItem;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportResult;
import com.rigour.erp.api.v1.model.CustomerTypePriceItem;
import com.rigour.erp.api.v1.model.CustomerTypePriceSyncCommand;
import com.rigour.erp.api.v1.model.CustomerTypePriceView;
import com.rigour.erp.application.port.out.ErpCustomerTypePriceStore;
import com.rigour.erp.application.service.support.ErpServiceValidation;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** ERP 客户类型等级价维护用例；按商品批量查询，按商品规格整体保存等级价。 */
@Service
public final class ErpCustomerTypePriceService {
    private static final Logger log = LoggerFactory.getLogger(ErpCustomerTypePriceService.class);
    private static final String READ_PERMISSION = "erp:product-price:read";
    private static final String WRITE_PERMISSION = "erp:product-price:write";
    private static final int MAX_PRODUCT_IDS = 200;
    private static final int MAX_PRICE_ITEMS = 200;
    private static final int MAX_IMPORT_ITEMS = 2000;

    private final ErpCustomerTypePriceStore store;

    public ErpCustomerTypePriceService(ErpCustomerTypePriceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public List<CustomerTypePriceView> prices(List<Long> productIds) {
        String tenantId = tenant(READ_PERMISSION);
        List<Long> normalized = normalizeProductIds(productIds);
        if (normalized.isEmpty()) return List.of();
        List<CustomerTypePriceView> result = store.pricesByProductIds(tenantId, normalized);
        log.debug("ERP客户类型等级价查询完成 tenantId={} productCount={} priceCount={}",
                tenantId, normalized.size(), result.size());
        return result;
    }

    public List<CustomerTypePriceView> syncVariant(Long productVariantId, CustomerTypePriceSyncCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        Long variantId = ErpServiceValidation.requireId(productVariantId, "商品规格ID无效");
        List<CustomerTypePriceItem> items = normalizeItems(command);
        List<CustomerTypePriceView> result = store.syncVariantPrices(
                actor.tenantId().toString(), variantId, items, actor.principalId().toString());
        log.info("ERP客户类型等级价保存完成 tenantId={} variantId={} itemCount={} priceCount={} actorId={}",
                actor.tenantId(), variantId, items.size(), result.size(), actor.principalId());
        return result;
    }

    public CustomerTypePriceImportResult importPrices(CustomerTypePriceImportCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        List<CustomerTypePriceImportItem> items = normalizeImportItems(command);
        CustomerTypePriceImportResult result = store.importPrices(
                actor.tenantId().toString(), items, actor.principalId().toString());
        log.info("ERP客户类型等级价导入完成 tenantId={} total={} created={} updated={} actorId={}",
                actor.tenantId(), result.total(), result.created(), result.updated(), actor.principalId());
        return result;
    }

    private static List<Long> normalizeProductIds(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return List.of();
        if (productIds.size() > MAX_PRODUCT_IDS) {
            throw badRequest("一次最多查询" + MAX_PRODUCT_IDS + "个商品");
        }
        List<Long> normalized = new ArrayList<>();
        for (Long productId : productIds) {
            normalized.add(ErpServiceValidation.requireId(productId, "productId无效"));
        }
        return normalized;
    }

    private static List<CustomerTypePriceItem> normalizeItems(CustomerTypePriceSyncCommand command) {
        if (command == null) throw badRequest("客户类型等级价参数不能为空");
        List<CustomerTypePriceItem> items = command.items();
        if (items.size() > MAX_PRICE_ITEMS) {
            throw badRequest("一次最多保存" + MAX_PRICE_ITEMS + "条客户类型等级价");
        }
        List<CustomerTypePriceItem> normalized = new ArrayList<>();
        Set<String> typeCodes = new HashSet<>();
        for (CustomerTypePriceItem item : items) {
            if (item == null) throw badRequest("客户类型等级价明细不能为空");
            String typeCode = ErpServiceValidation.required(item.customerTypeCode(), "customerTypeCode不能为空", 128);
            if (!typeCodes.add(typeCode)) throw badRequest("客户类型不能重复：" + typeCode);
            normalized.add(new CustomerTypePriceItem(
                    typeCode,
                    requiredMoney(item.salePrice()),
                    ErpServiceValidation.text(item.remark(), 500, "remark")));
        }
        return normalized;
    }

    private static List<CustomerTypePriceImportItem> normalizeImportItems(
            CustomerTypePriceImportCommand command) {
        if (command == null || command.items().isEmpty()) throw badRequest("没有可导入的等级价");
        List<CustomerTypePriceImportItem> items = command.items();
        if (items.size() > MAX_IMPORT_ITEMS) {
            throw badRequest("一次最多导入" + MAX_IMPORT_ITEMS + "条等级价");
        }
        List<CustomerTypePriceImportItem> normalized = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (CustomerTypePriceImportItem item : items) {
            if (item == null) throw badRequest("导入明细不能为空");
            Long variantId = ErpServiceValidation.requireId(item.productVariantId(), "商品规格ID无效");
            String typeCode = ErpServiceValidation.required(item.customerTypeCode(), "customerTypeCode不能为空", 128);
            if (!keys.add(variantId + "::" + typeCode)) throw badRequest("导入明细中商品规格与客户类型重复");
            normalized.add(new CustomerTypePriceImportItem(
                    variantId,
                    typeCode,
                    requiredMoney(item.salePrice()),
                    ErpServiceValidation.text(item.remark(), 500, "remark")));
        }
        return normalized;
    }

    private static BigDecimal requiredMoney(BigDecimal value) {
        if (value == null) throw badRequest("salePrice不能为空");
        if (value.signum() <= 0) throw badRequest("salePrice必须大于0");
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
}
