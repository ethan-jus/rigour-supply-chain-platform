package com.rigour.sales.temporarycheckin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 既有管理员 Cookie/CSRF 保护下的显式补解析入口；GET 仅查本地事实，不产生地图调用。 */
@RestController
@RequestMapping("/sales-checkin/admin/api/v1/submissions")
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
class TemporaryCheckinAddressController {
    private final TemporaryCheckinAddressService service;
    private final TemporaryCheckinAdminAccessPolicy access;
    TemporaryCheckinAddressController(TemporaryCheckinAddressService service,TemporaryCheckinAdminAccessPolicy access) {
        this.service=service; this.access=access;
    }
    @GetMapping("/{id}/address")
    ResponseEntity<TemporaryCheckinAddressService.AddressView> get(HttpServletRequest request,@PathVariable("id") UUID id) {
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(service.get(access.requireScope(request),id));
    }
    @PostMapping("/{id}/address/resolve")
    ResponseEntity<TemporaryCheckinAddressService.AddressView> resolve(HttpServletRequest request,@PathVariable("id") UUID id) {
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(service.resolve(access.requireScope(request),id));
    }
    @PostMapping("/addresses/resolve")
    ResponseEntity<TemporaryCheckinAddressService.BatchAddressView> resolveBatch(HttpServletRequest request,@RequestBody BatchRequest body) {
        return ResponseEntity.ok().header("Cache-Control","private, no-store")
                .body(service.resolveBatch(access.requireScope(request),body==null ? null : body.submissionIds()));
    }
    record BatchRequest(List<UUID> submissionIds) { }
}
