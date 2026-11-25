package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.PaymentFilterApi.*;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PaymentFilterService {
    private final PaymentFilterStore store;
    private final CrmCustomerAreaDisplayClient crm;
    private final HrEmployeeDisplayClient hr;
    private final PaymentFilterCatalogClient catalog;
    public PaymentFilterService(PaymentFilterStore store, CrmCustomerAreaDisplayClient crm,
                                HrEmployeeDisplayClient hr, PaymentFilterCatalogClient catalog) {
        this.store = store; this.crm = crm; this.hr = hr; this.catalog = catalog;
    }
    public Options options(RegisterPage page) {
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(page.permission());
        var visible = store.visible(actor.tenantId().toString(), page);
        var names = new HashMap<String,String>();
        if (!visible.areas().isEmpty())
            crm.resolve(serviceCaller(actor, "crm:customer:read"), visible.areas())
                    .forEach(a -> names.put(a.areaCode(),a.areaName()));
        var areas = visible.areas().stream().sorted().map(code -> new Choice(code,names.getOrDefault(code,code))).toList();
        var departments = new ArrayList<Department>();
        if (!visible.departments().isEmpty()) {
            var directory = new HashMap<Long,HrEmployeeDisplayClient.DepartmentDisplay>();
            hr.departments(serviceCaller(actor,"hr:employee:read")).forEach(d -> directory.put(d.id(),d));
            Set<Long> included=new LinkedHashSet<>(visible.departments().keySet());
            // 只补可见部门彼此之间的层级，不展示无关组织分支。
            for (var id:visible.departments().keySet()) {
                var intermediate=new LinkedHashSet<Long>();
                var row=directory.get(id);
                while (row!=null && row.parentId()!=null && intermediate.add(row.parentId())) {
                    if (visible.departments().containsKey(row.parentId())) {
                        included.addAll(intermediate);
                        break;
                    }
                    row=directory.get(row.parentId());
                }
            }
            for (var id:included) {
                var row=directory.get(id);
                departments.add(new Department(id,row==null?null:row.parentId(),
                        row==null?Objects.toString(visible.departments().get(id),id.toString()):row.name(),0));
            }
        }
        if (visible.products().isEmpty())
            return new Options(areas,departments,visible.employees(),visible.creators(),List.of(),List.of(),visible.paymentEmployees());
        var ids = visible.products().stream().map(Product::id).toList();
        var metadata=catalog.catalog(serviceCaller(actor,"erp:product:read"),ids);
        var managed = new HashMap<String,Product>();
        metadata.products().forEach(p -> managed.put(p.id(),p));
        var products = visible.products().stream().map(p -> {
            var m=managed.get(p.id());
            return m==null?p:new Product(p.id(),p.productCode(),p.productName(),m.categoryId(),p.variants(),
                    m.mainImageUrl(),m.unitCode(),m.middleUnitCode(),m.baseToMiddleRate(),
                    m.bigUnitCode(),m.baseToBigRate(),m.statisticsUnitLevel());
        }).toList();
        Set<String> categoryIds=new HashSet<>();
        var parents=new HashMap<String,String>();
        metadata.categories().forEach(c -> parents.put(c.id(),c.parentId()));
        for (var product:products)
            for (String id=product.categoryId();id!=null && categoryIds.add(id);id=parents.get(id)) {}
        return new Options(areas,departments,visible.employees(),visible.creators(),
                metadata.categories().stream().filter(c -> categoryIds.contains(c.id())).toList(),products,visible.paymentEmployees());
    }
    private static CallerIdentity serviceCaller(CallerIdentity actor, String permission) {
        return new CallerIdentity("SERVICE", UUID.nameUUIDFromBytes(
                "service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8)), actor.tenantId(),
                null, null, UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of(permission));
    }
}
