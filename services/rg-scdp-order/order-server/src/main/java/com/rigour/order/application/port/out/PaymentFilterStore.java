package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.PaymentFilterApi.*;
import com.rigour.order.application.service.sales.RegisterPage;
import java.util.*;

public interface PaymentFilterStore {
    record Visible(List<Person> employees, List<String> creators, Set<String> areas,
                   Map<Long,String> departments, List<Product> products, List<Person> paymentEmployees) {}
    Visible visible(String tenant, RegisterPage page);
}
