package com.rigour.erp.api.v1.model;

import java.util.List;

/** ERP 客户类型等级价整体保存命令；明细是该商品规格下客户类型等级的完整集合，未提交的客户类型将被清除。 */
public record CustomerTypePriceSyncCommand(List<CustomerTypePriceItem> items) {
    public CustomerTypePriceSyncCommand {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
