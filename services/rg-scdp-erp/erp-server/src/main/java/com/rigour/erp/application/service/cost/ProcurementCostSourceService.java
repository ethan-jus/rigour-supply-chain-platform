package com.rigour.erp.application.service.cost;

import com.rigour.erp.api.v1.model.ProcurementCostSource;
import com.rigour.erp.application.port.out.ProcurementCostSources;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public final class ProcurementCostSourceService {
    private final ProcurementCostSources store;
    public ProcurementCostSourceService(ProcurementCostSources store) { this.store = store; }
    public ProcurementCostSource source(String code) {
        String permission="erp:procurement:cost-source-read";
        var actor=AuthorizationContext.requireCurrent();
        var expected=UUID.nameUUIDFromBytes("service:rigour-order-center-service".getBytes(StandardCharsets.UTF_8));
        if (!"SERVICE".equals(actor.principalScope()) || actor.tenantId()==null || !expected.equals(actor.principalId())) throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        if (code==null || !code.matches("[A-Za-z0-9_-]{1,50}")) throw new BusinessException(ErrorCode.BAD_REQUEST,"采购单号无效",List.of());
        return store.source(actor.tenantId().toString(),code).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"已确认采购来源不存在",List.of()));
    }
}
