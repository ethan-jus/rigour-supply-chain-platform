package com.rigour.order.api.controller;
import com.rigour.order.application.port.out.SupplyReadinessStore;
import com.rigour.tenant.iam.api.v1.model.SupplyReadinessView;
import com.rigour.shared.context.*;
import org.springframework.web.bind.annotation.*;
/** 受信服务专用检查，不允许浏览器身份直接读取切换统计。 */
@RestController
public final class SupplyReadinessController {
 private final SupplyReadinessStore store; public SupplyReadinessController(SupplyReadinessStore store){this.store=store;}
 @GetMapping("/internal/v1/supply/readiness")
 public SupplyReadinessView inspect(){var a=AuthorizationContext.requireCurrent();if(!"SERVICE".equals(a.principalScope())||a.tenantId()==null)throw new AuthorizationDeniedException("service-readiness");AuthorizationContext.requirePermission("supply:readiness:read");return store.inspect(a.tenantId().toString());}
}
