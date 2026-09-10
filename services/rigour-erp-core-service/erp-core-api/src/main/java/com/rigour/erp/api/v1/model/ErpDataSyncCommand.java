package com.rigour.erp.api.v1.model;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Instant;
import java.util.List;

/** Portal 请求 ERP 同步一类订货宝数据的统一参数。 */
public record ErpDataSyncCommand(
        /** 商品、供应商、采购、仓储或库存对象类型。 */
        String objectType,
        /** 最多读取页数/库存批次数，范围 1..100；省略时为 100。 */
        Integer maxPages,
        /** 来源数据窗口开始时间；必须与 to 同时提供。 */
        Instant from,
        /** 来源数据窗口结束时间；必须与 from 同时提供。 */
        Instant to) {

    public ErpDataSyncCommand {
        if (maxPages != null && (maxPages < 1 || maxPages > 100)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxPages必须在1到100之间", List.of());
        }
        if ((from == null) != (to == null)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "同步窗口from和to必须同时提供", List.of());
        }
        if (from != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "同步窗口from必须早于to", List.of());
        }
    }

    public ErpDataSyncCommand(String objectType, Integer maxPages) {
        this(objectType, maxPages, null, null);
    }

    public int effectiveMaxPages() {
        return maxPages == null ? 100 : maxPages;
    }
}
