package com.rigour.erp.application.port.out;

import com.rigour.erp.domain.model.product.Brand;
import com.rigour.erp.domain.model.product.Category;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.product.Specification;
import com.rigour.erp.domain.model.product.Tag;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.UUID;

/** ERP 调用 Integration 商品主数据契约的出站端口；实现不得直接访问订货宝。 */
public interface DhbProductMasterDataClient {

    Collected collect(CallerIdentity serviceCaller, UUID connectorId,
                      MasterDataObjectType objectType, int maxPages);

    record Collected(MasterDataObjectType objectType, long total, int pages,
                     List<Product> products, List<Category> categories,
                     List<Brand> brands, List<Specification> specifications,
                     List<Tag> tags) {
        public Collected {
            products = products == null ? List.of() : List.copyOf(products);
            categories = categories == null ? List.of() : List.copyOf(categories);
            brands = brands == null ? List.of() : List.copyOf(brands);
            specifications = specifications == null ? List.of() : List.copyOf(specifications);
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }
}
