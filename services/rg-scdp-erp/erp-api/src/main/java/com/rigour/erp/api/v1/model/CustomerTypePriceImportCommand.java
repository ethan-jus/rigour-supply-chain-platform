package com.rigour.erp.api.v1.model;

import java.util.List;

/** 客户类型等级价批量导入命令；明细为目标规格与客户类型的等级价集合。 */
public record CustomerTypePriceImportCommand(List<CustomerTypePriceImportItem> items) {
    public CustomerTypePriceImportCommand {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
