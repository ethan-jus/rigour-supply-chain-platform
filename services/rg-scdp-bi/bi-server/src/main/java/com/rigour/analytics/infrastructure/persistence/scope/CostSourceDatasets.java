package com.rigour.analytics.infrastructure.persistence.scope;

import java.util.*;

/** 成本源契约与所属域的最小列目录对应；不引用历史城市成本或前端样例。 */
public final class CostSourceDatasets {
    private CostSourceDatasets() {}

    public enum KeyType {
        TEXT_UUID
    }

    public record Dataset(String source, String code, KeyType keyType, List<String> columns) {}

    public static final List<Dataset> ALL =
            List.of(
                    new Dataset(
                            "ERP",
                            "ERP_COST_ENTRY",
                            KeyType.TEXT_UUID,
                            List.of(
                                    "id",
                                    "tenant_id",
                                    "document_no",
                                    "title",
                                    "type_code",
                                    "city_code",
                                    "department_id",
                                    "cost_date",
                                    "amount",
                                    "status_code",
                                    "payment_status_code",
                                    "data_json",
                                    "revision",
                                    "created_by",
                                    "updated_time",
                                    "category_code",
                                    "premises_type",
                                    "source_system",
                                    "owner_employee_code",
                                    "owner_employee_name",
                                    "warehouse_id",
                                    "source_warehouse_id",
                                    "warehouse_name",
                                    "sources_json")),
                    new Dataset(
                            "ORDER",
                            "ORDER_LOGISTICS_COST",
                            KeyType.TEXT_UUID,
                            List.of(
                                    "id",
                                    "tenant_id",
                                    "document_no",
                                    "title",
                                    "type_code",
                                    "city_code",
                                    "department_id",
                                    "cost_date",
                                    "amount",
                                    "status_code",
                                    "payment_status_code",
                                    "data_json",
                                    "revision",
                                    "created_by",
                                    "updated_time",
                                    "category_code",
                                    "payer_code",
                                    "source_system",
                                    "owner_employee_code",
                                    "owner_employee_name",
                                    "warehouse_id",
                                    "source_warehouse_id",
                                    "warehouse_name",
                                    "sources_json")),
                    new Dataset(
                            "HR",
                            "HR_PAYROLL_COST",
                            KeyType.TEXT_UUID,
                            List.of(
                                    "id",
                                    "tenant_id",
                                    "document_no",
                                    "title",
                                    "type_code",
                                    "city_code",
                                    "department_id",
                                    "cost_date",
                                    "amount",
                                    "status_code",
                                    "payment_status_code",
                                    "data_json",
                                    "revision",
                                    "created_by",
                                    "updated_time",
                                    "employee_code",
                                    "employee_name",
                                    "department_name",
                                    "category_code",
                                    "components_json")),
                    new Dataset(
                            "HR",
                            "HR_EXPENSE",
                            KeyType.TEXT_UUID,
                            List.of(
                                    "id",
                                    "tenant_id",
                                    "document_no",
                                    "title",
                                    "type_code",
                                    "city_code",
                                    "department_id",
                                    "cost_date",
                                    "amount",
                                    "status_code",
                                    "payment_status_code",
                                    "data_json",
                                    "revision",
                                    "created_by",
                                    "updated_time",
                                    "employee_code",
                                    "employee_name",
                                    "department_name")));

    public static String validateCursor(String value, String previous) {
        if (value == null
                || !value.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalStateException("TEXT_UUID源记录标识无效");
        String next = value.toLowerCase(Locale.ROOT);
        if (previous != null
                && !previous.isEmpty()
                && next.compareTo(previous.toLowerCase(Locale.ROOT)) <= 0)
            throw new IllegalStateException("TEXT_UUID源投影游标未前进");
        return next;
    }
}
