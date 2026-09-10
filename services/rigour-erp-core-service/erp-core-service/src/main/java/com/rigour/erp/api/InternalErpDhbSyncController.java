package com.rigour.erp.api;

import com.rigour.erp.api.v1.model.ErpDataSyncCommand;
import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.erp.application.service.sync.ErpDataSyncService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integration 统一订货宝编排器调用 ERP 同步的内部入口。 */
@RestController
@RequestMapping("/internal/v1/erp/dhb")
public final class InternalErpDhbSyncController {
    private final ErpDataSyncService service;

    public InternalErpDhbSyncController(ErpDataSyncService service) {
        this.service = service;
    }

    @PostMapping("/sync")
    public ApiResponse<ErpDataSyncResult> sync(@RequestBody InternalErpDhbSyncCommand command) {
        if (command == null || command.connectorId() == null || command.sourceTaskId() == null) {
            throw new IllegalArgumentException("connectorId和sourceTaskId不能为空");
        }
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        return ApiResponse.success(service.runInternal(caller, command.connectorId(), command.sourceTaskId(),
                triggerType(command.triggerType()),
                new ErpDataSyncCommand(command.objectType(), command.maxPages(), command.from(), command.to())));
    }

    public record InternalErpDhbSyncCommand(UUID connectorId, UUID sourceTaskId,
                                            String objectType, Integer maxPages,
                                            String triggerType, Instant from, Instant to) { }

    private static String triggerType(String value) {
        return value == null || value.isBlank() ? "SCHEDULED" : value.strip().toUpperCase(Locale.ROOT);
    }
}
