package com.rigour.order.application.service.sales;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import com.rigour.order.application.port.out.HrEmployeeDisplayClient.DepartmentDisplay;
import com.rigour.order.application.port.out.CrmCustomerAreaDisplayClient.CustomerAreaDisplay;
import java.util.*;
class OrderDepartmentRegionsTest {
    @Test void mapsSalesCitiesBelowProvinceAndRejectsAmbiguousNames() {
        var departments = List.of(new DepartmentDisplay(5L,null,"销售部","ACTIVE"),
            new DepartmentDisplay(27L,5L,"河北省","ACTIVE"),new DepartmentDisplay(11L,27L,"石家庄","ACTIVE"),
            new DepartmentDisplay(28L,27L,"唐山市","ACTIVE"),new DepartmentDisplay(99L,null,"唐山市","ACTIVE"));
        var areas = List.of(new CustomerAreaDisplay("SJZ","石家庄市"),new CustomerAreaDisplay("TS","唐山市"));
        assertThat(OrderDepartmentRegions.ids(departments,areas)).containsExactlyInAnyOrderEntriesOf(Map.of("SJZ",11L,"TS",28L));
        var duplicate = new ArrayList<>(departments);duplicate.add(new DepartmentDisplay(29L,5L,"唐山","ACTIVE"));
        assertThat(OrderDepartmentRegions.ids(duplicate,areas)).containsOnlyKeys("SJZ");
    }
}
