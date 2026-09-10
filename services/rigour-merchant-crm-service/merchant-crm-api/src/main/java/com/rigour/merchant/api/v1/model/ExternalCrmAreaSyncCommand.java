package com.rigour.merchant.api.v1.model;

import java.util.List;

/** 外部区域/城市批量同步命令。 */
public record ExternalCrmAreaSyncCommand(
        String sourceSystem,
        List<ExternalCrmAreaRowCommand> rows) {
}
