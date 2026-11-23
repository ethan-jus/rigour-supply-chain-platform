package com.rigour.analytics.application.port.out;

import com.rigour.analytics.application.model.SalesOverview.*;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface SalesOverviewStore {
    record Owner(
            String code, String name, String city, BigDecimal booked, BigDecimal received,
            BigDecimal applied) {}

    List<Owner> owners(
            ReceivablesStore.Scope scope, LocalDate from, LocalDate asOf, boolean finance,
            boolean receipts);

    boolean receiptsAvailable(String tenant);

    List<Department> departments(String tenant);

    List<Target> targets(String tenant, String fromMonth, String toMonth);

    DetailPage details(
            ReceivablesStore.Scope scope,
            LocalDate from,
            LocalDate asOf,
            DetailKind kind,
            boolean finance,
            int page,
            int size);
}
