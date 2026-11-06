package com.rigour.order.application.service.cost;
import com.rigour.shared.context.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
@Service
public final class ProcurementFreightSyncService {
    /** 滚动发布兼容旧 ERP 请求，仍校验服务身份，但不再生成或更新物流成本。 */
    public String sync(String code){
        var a=AuthorizationContext.requireCurrent();String permission="order:logistics-cost:procurement-sync";
        var expected=UUID.nameUUIDFromBytes("service:rigour-erp-core-service".getBytes(StandardCharsets.UTF_8));
        if(!"SERVICE".equals(a.principalScope()) || a.tenantId()==null || !expected.equals(a.principalId()))throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        if(code==null || !code.matches("[A-Za-z0-9_-]{1,50}"))throw new IllegalArgumentException("采购单号无效");
        return "ERP_PURCHASE_COST";
    }
}
