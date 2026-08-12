package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.BrandView;
import com.rigour.erp.api.v1.model.CategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductPageView;
import com.rigour.erp.api.v1.model.SkuPageView;
import com.rigour.erp.api.v1.model.SpecificationView;
import com.rigour.erp.api.v1.model.TagView;
import com.rigour.erp.application.port.out.ProductMasterDataStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;

/** ERP 商品主数据本地查询用例；不会在查询链路访问 Integration 或订货宝。 */
@Service
public final class ProductMasterDataQueryService {
    private final ProductMasterDataStore store;

    public ProductMasterDataQueryService(ProductMasterDataStore store) {
        this.store = store;
    }

    public ProductPageView products(String tenantId, int begin, int step, String query,
                                    String internalStatus, String sourcePutaway) {
        requireRead();
        validatePage(begin, step);
        return store.products(tenantId, begin, step, query, internalStatus, sourcePutaway);
    }

    public SkuPageView skus(String tenantId, int begin, int step, String query,
                            String internalStatus, String sourcePutaway) {
        requireRead();
        validatePage(begin, step);
        return store.skus(tenantId, begin, step, query, internalStatus, sourcePutaway);
    }

    public MasterDataPageView<CategoryView> categories(String tenantId, int begin, int step,
                                                       String query, String status) {
        requireRead(); validatePage(begin, step);
        return store.categories(tenantId, begin, step, query, status);
    }

    public MasterDataPageView<BrandView> brands(String tenantId, int begin, int step,
                                                String query, String status) {
        requireRead(); validatePage(begin, step);
        return store.brands(tenantId, begin, step, query, status);
    }

    public MasterDataPageView<SpecificationView> specifications(String tenantId, int begin, int step,
                                                                String query, String status) {
        requireRead(); validatePage(begin, step);
        return store.specifications(tenantId, begin, step, query, status);
    }

    public MasterDataPageView<TagView> tags(String tenantId, int begin, int step,
                                            String query, String status) {
        requireRead(); validatePage(begin, step);
        return store.tags(tenantId, begin, step, query, status);
    }

    private static void requireRead() {
        AuthorizationContext.requirePermission("erp:product:read");
    }

    private static void validatePage(int begin, int step) {
        if (begin < 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "begin不能小于0", List.of());
        if (step < 1 || step > 1000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "step必须在1到1000之间", List.of());
        }
    }
}
