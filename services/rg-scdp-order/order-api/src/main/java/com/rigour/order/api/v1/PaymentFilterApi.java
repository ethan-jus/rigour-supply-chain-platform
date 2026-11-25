package com.rigour.order.api.v1;

import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;

/** 回款查询专用选项，仅暴露筛选所需字段。 */
public interface PaymentFilterApi {
    String PATH = OrderRegisterApi.BASE_PATH + "/payments/filter-options";
    @GetMapping(PATH)
    ApiResponse<Options> options();

    @GetMapping(OrderRegisterApi.BASE_PATH + "/orders/filter-options")
    ApiResponse<Options> orderOptions();
    @GetMapping(OrderRegisterApi.BASE_PATH + "/lines/filter-options")
    ApiResponse<Options> lineOptions();

    record Choice(String code, String name) {}
    record Person(String employeeCode, String employeeName, String regionCode) {}
    record Department(Long id, Long parentId, String departmentName, int sortOrder) {}
    record Category(String id, String parentId, String categoryName) {}
    record Variant(String id, String specificationSnapshot) {}
    record Product(String id, String productCode, String productName, String categoryId, List<Variant> variants,
                   String mainImageUrl, String unitCode, String middleUnitCode, Double baseToMiddleRate,
                   String bigUnitCode, Double baseToBigRate, String statisticsUnitLevel) {
        public Product(String id, String productCode, String productName, String categoryId, List<Variant> variants) {
            this(id, productCode, productName, categoryId, variants, null,null,null,null,null,null,null);
        }
    }
    record Catalog(List<Category> categories, List<Product> products) {}
    record Options(List<Choice> areas, List<Department> departments, List<Person> employees,
                   List<String> creators, List<Category> categories, List<Product> products,List<Person> paymentEmployees) {}
}
