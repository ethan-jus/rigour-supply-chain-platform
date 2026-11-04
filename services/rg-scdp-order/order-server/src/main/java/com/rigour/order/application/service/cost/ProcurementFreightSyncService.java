package com.rigour.order.application.service.cost;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
@Service
public final class ProcurementFreightSyncService {
    private final ProcurementFreightSource source;private final OrderLogisticsCostStore store;
    public ProcurementFreightSyncService(ProcurementFreightSource source,OrderLogisticsCostStore store){this.source=source;this.store=store;}
    public String sync(String code){
        var a=AuthorizationContext.requireCurrent();String permission="order:logistics-cost:procurement-sync";
        var expected=UUID.nameUUIDFromBytes("service:rigour-erp-core-service".getBytes(StandardCharsets.UTF_8));
        if(!"SERVICE".equals(a.principalScope()) || a.tenantId()==null || !expected.equals(a.principalId()))throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        if(code==null || !code.matches("[A-Za-z0-9_-]{1,50}"))throw new IllegalArgumentException("采购单号无效");
        var actual=source.source(a.tenantId().toString(),code);
        if(!code.equals(actual.procurementNo()))throw new IllegalStateException("采购来源单号不一致");
        if(actual.cityCode()==null || actual.cityCode().isBlank())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "采购收货仓库缺少归属城市，请维护仓库档案后重试", java.util.List.of());
        return store.upsertProcurementFreight(a.tenantId().toString(),ProcurementFreightDraft.from(actual)).id();
    }
}
