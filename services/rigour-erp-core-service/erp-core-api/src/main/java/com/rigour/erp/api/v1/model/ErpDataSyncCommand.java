package com.rigour.erp.api.v1.model;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;

/** Portal 请求 ERP 同步一类订货宝数据的统一参数。 */
public record ErpDataSyncCommand(
        /** 商品、供应商、采购、仓储或库存对象类型。 */
        String objectType,
        /** 最多读取页数/库存批次数，范围 1..100；省略时为 100。 */
        Integer maxPages) {

    public ErpDataSyncCommand {
        if (maxPages != null && (maxPages < 1 || maxPages > 100)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxPages必须在1到100之间", List.of());
        }
    }

    public int effectiveMaxPages() {
        return maxPages == null ? 100 : maxPages;
    }
}
