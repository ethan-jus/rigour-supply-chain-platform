package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.AdminSalespersonPage;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.AdminSalespersonView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.ResetSalespersonCredentialRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SaveSalespersonRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SalespersonCredentialView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SubmissionDeletionRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SubmissionDeletionView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 后台销售目录与少量测试记录的受控物理删除接口。 */
@RestController
@RequestMapping("/sales-checkin/admin/api/v1")
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
final class TemporaryCheckinGovernanceController {

    private final TemporaryCheckinAdminAccessPolicy accessPolicy;
    private final TemporaryCheckinSalespersonAdminService salespersonService;
    private final TemporaryCheckinDeletionService deletionService;

    TemporaryCheckinGovernanceController(
            TemporaryCheckinAdminAccessPolicy accessPolicy,
            TemporaryCheckinSalespersonAdminService salespersonService,
            TemporaryCheckinDeletionService deletionService) {
        this.accessPolicy = accessPolicy;
        this.salespersonService = salespersonService;
        this.deletionService = deletionService;
    }

    @GetMapping("/salespersons")
    AdminSalespersonPage salespersons(
            HttpServletRequest request,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return salespersonService.list(accessPolicy.currentPrincipal(request),
                city, status, query, page, size);
    }

    @PostMapping("/salespersons")
    @ResponseStatus(HttpStatus.CREATED)
    SalespersonCredentialView createSalesperson(
            HttpServletRequest request, @RequestBody SaveSalespersonRequest body) {
        return salespersonService.create(accessPolicy.currentPrincipal(request), body);
    }

    @PatchMapping("/salespersons/{id}")
    AdminSalespersonView updateSalesperson(
            HttpServletRequest request,
            @PathVariable("id") UUID id,
            @RequestBody SaveSalespersonRequest body) {
        return salespersonService.update(accessPolicy.currentPrincipal(request), id, body);
    }

    @PostMapping("/salespersons/{id}/credential-reset")
    SalespersonCredentialView resetSalespersonCredential(
            HttpServletRequest request,
            @PathVariable("id") UUID id,
            @RequestBody(required = false) ResetSalespersonCredentialRequest body) {
        String reason = body == null ? null : body.reason();
        return salespersonService.resetCredential(accessPolicy.currentPrincipal(request), id, reason);
    }

    @PostMapping("/submission-deletions")
    SubmissionDeletionView deleteSubmissions(
            HttpServletRequest request, @RequestBody SubmissionDeletionRequest body) {
        TemporaryCheckinAdminPrincipal principal = accessPolicy.currentPrincipal(request);
        return deletionService.delete(principal.username(), principal.city(), body);
    }
}
