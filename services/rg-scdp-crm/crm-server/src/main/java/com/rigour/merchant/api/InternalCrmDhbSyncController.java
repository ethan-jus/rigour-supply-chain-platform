package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.merchant.application.service.CrmMasterDataSyncService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integration 统一订货宝编排器调用 CRM 同步的内部入口。 */
@RestController
@RequestMapping("/internal/v1/crm/dhb")
public final class InternalCrmDhbSyncController {
    private final CrmMasterDataSyncService service;

    public InternalCrmDhbSyncController(CrmMasterDataSyncService service) {
        this.service = service;
    }

    @PostMapping(value = "/sync", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SyncResult> sync(@RequestBody InternalCrmDhbSyncCommand command) {
        if (command == null || command.connectorId() == null || command.sourceTaskId() == null) {
            throw new IllegalArgumentException("connectorId和sourceTaskId不能为空");
        }
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        return ApiResponse.success(service.runSelected(caller, command.connectorId(),
                command.sourceTaskId(), command.maxPages() == null ? 100 : command.maxPages(),
                command.from(), command.to(), command.objectType(),
                Boolean.TRUE.equals(command.incremental()), command.createdBefore(), command.initiatedBy()));
    }

    public record InternalCrmDhbSyncCommand(UUID connectorId, UUID sourceTaskId, Integer maxPages,
                                            Instant from, Instant to, String objectType,
                                            Boolean incremental, Instant createdBefore, UUID initiatedBy) {
        public InternalCrmDhbSyncCommand(UUID connectorId, UUID sourceTaskId, Integer maxPages, Instant from, Instant to, String objectType, Boolean incremental, Instant createdBefore) {
            this(connectorId, sourceTaskId, maxPages, from, to, objectType, incremental, createdBefore, null);
        }
        public InternalCrmDhbSyncCommand(UUID connectorId, UUID sourceTaskId, Integer maxPages,
                Instant from, Instant to, String objectType) {
            this(connectorId, sourceTaskId, maxPages, from, to, objectType, false, null);
        }
        public InternalCrmDhbSyncCommand(UUID connectorId, UUID sourceTaskId, Integer maxPages, Instant from, Instant to) {
            this(connectorId, sourceTaskId, maxPages, from, to, null);
        }
    }
}
