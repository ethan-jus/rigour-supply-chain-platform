package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.AdminSalespersonPage;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.AdminSalespersonView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SaveSalespersonRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SalespersonBootstrapCredential;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SalespersonBootstrapResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SalespersonCredentialView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalesIdentityService.IssuedTemporaryCode;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalespersonAdminRepository.SalespersonAdminRow;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 城市范围内的销售目录维护；删除通过停用/离职表达，历史快照保持不变。 */
@Service
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinSalespersonAdminService {

    private final TemporaryCheckinSalespersonAdminRepository repository;
    private final TemporaryCheckinAdminAuthRepository cityRepository;
    private final TemporaryCheckinSalesIdentityService identityService;
    private final Clock clock;
    private final UUID tenantId;

    TemporaryCheckinSalespersonAdminService(
            TemporaryCheckinSalespersonAdminRepository repository,
            TemporaryCheckinAdminAuthRepository cityRepository,
            TemporaryCheckinSalesIdentityService identityService,
            TemporaryCheckinProperties properties,
            Clock clock) {
        this.repository = repository;
        this.cityRepository = cityRepository;
        this.identityService = identityService;
        this.clock = clock;
        this.tenantId = properties.requireTenantId();
    }

    AdminSalespersonPage list(
            TemporaryCheckinAdminPrincipal principal, String requestedCity, String rawStatus,
            String rawQuery, Integer requestedPage, Integer requestedSize) {
        String city = scopedCity(principal, optional(requestedCity, 64));
        String status = normalizeStatus(rawStatus, true);
        String query = optional(rawQuery, 64);
        String escaped = query == null ? null
                : query.replace("=", "==").replace("%", "=%").replace("_", "=_");
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? 50 : requestedSize;
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw TemporaryCheckinException.badRequest("销售目录分页范围无效");
        }
        long total = repository.count(tenantId, city, status, escaped);
        var items = repository.list(tenantId, city, status, escaped, page * size, size).stream()
                .map(TemporaryCheckinSalespersonAdminService::view)
                .toList();
        int totalPages = total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, ((total - 1) / size) + 1);
        return new AdminSalespersonPage(items, total, page, size, totalPages);
    }

    @Transactional
    SalespersonCredentialView create(
            TemporaryCheckinAdminPrincipal principal, SaveSalespersonRequest request) {
        NormalizedSalesperson normalized = normalize(principal, request, null);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            repository.insert(id, tenantId, normalized.name(), normalized.city(), normalized.position(),
                    normalized.employmentStatus(), normalized.status(), normalized.sortOrder(), now);
        } catch (DuplicateKeyException exception) {
            throw TemporaryCheckinException.conflict("该城市已存在同名销售");
        }
        IssuedTemporaryCode credential = identityService.issueTemporaryCode(
                tenantId, id, principal.username(), "新增销售并签发初始个人码");
        SalespersonAdminRow row = repository.find(tenantId, id)
                .orElseThrow(() -> TemporaryCheckinException.conflict("销售创建后读取失败"));
        return new SalespersonCredentialView(view(row), credential.temporaryCode());
    }

    @Transactional
    AdminSalespersonView update(
            TemporaryCheckinAdminPrincipal principal, UUID id, SaveSalespersonRequest request) {
        SalespersonAdminRow existing = requireScoped(principal, id);
        NormalizedSalesperson normalized = normalize(principal, request, existing.city());
        try {
            int updated = repository.update(tenantId, id,
                    principal.allCities() ? null : principal.city(), normalized.name(), normalized.city(),
                    normalized.position(), normalized.employmentStatus(), normalized.status(),
                    normalized.sortOrder(), clock.instant());
            if (updated != 1) throw TemporaryCheckinException.conflict("销售状态已变化，请刷新后重试");
        } catch (DuplicateKeyException exception) {
            throw TemporaryCheckinException.conflict("该城市已存在同名销售");
        }
        return view(repository.find(tenantId, id)
                .orElseThrow(() -> TemporaryCheckinException.notFound("销售不存在")));
    }

    @Transactional
    SalespersonCredentialView resetCredential(
            TemporaryCheckinAdminPrincipal principal, UUID id, String rawReason) {
        requireScoped(principal, id);
        String reason = required(rawReason, 512, "请填写重置原因");
        IssuedTemporaryCode code = identityService.issueTemporaryCode(
                tenantId, id, principal.username(), reason);
        SalespersonAdminRow row = repository.find(tenantId, id)
                .orElseThrow(() -> TemporaryCheckinException.notFound("销售不存在"));
        return new SalespersonCredentialView(view(row), code.temporaryCode());
    }

    SalespersonBootstrapResponse bootstrapUnconfiguredCredentials() {
        var credentials = repository.listWithoutCredential(tenantId, 1_000).stream()
                .map(row -> {
                    IssuedTemporaryCode issued = identityService.issueTemporaryCode(
                            tenantId, row.id(), "internal-bootstrap", "首次启用销售本人身份验证");
                    return new SalespersonBootstrapCredential(
                            row.id(), row.name(), row.city(), issued.temporaryCode());
                })
                .toList();
        return new SalespersonBootstrapResponse(credentials);
    }

    private SalespersonAdminRow requireScoped(TemporaryCheckinAdminPrincipal principal, UUID id) {
        if (id == null) throw TemporaryCheckinException.badRequest("salespersonId不能为空");
        SalespersonAdminRow row = repository.find(tenantId, id)
                .orElseThrow(() -> TemporaryCheckinException.notFound("销售不存在"));
        if (!principal.allCities() && !principal.city().equals(row.city())) {
            throw TemporaryCheckinException.adminForbidden("不能维护其他城市的销售");
        }
        return row;
    }

    private NormalizedSalesperson normalize(
            TemporaryCheckinAdminPrincipal principal, SaveSalespersonRequest request, String currentCity) {
        if (request == null) throw TemporaryCheckinException.badRequest("销售资料不能为空");
        String name = required(request.name(), 128, "销售姓名不能为空");
        String city = scopedCity(principal, required(request.city(), 64, "城市不能为空"));
        if (!cityRepository.existsActiveCity(tenantId, city)) {
            throw TemporaryCheckinException.badRequest("城市不存在或未启用");
        }
        if (!principal.allCities() && currentCity != null && !currentCity.equals(city)) {
            throw TemporaryCheckinException.adminForbidden("城市管理员不能转移销售城市");
        }
        String position = optional(request.position(), 64);
        String employmentStatus = request.employmentStatus() == null
                ? "在职" : required(request.employmentStatus(), 24, "在职状态不能为空");
        String status = normalizeStatus(request.status(), false);
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        if (sortOrder < 0 || sortOrder > 1_000_000) {
            throw TemporaryCheckinException.badRequest("排序必须在0到1000000之间");
        }
        return new NormalizedSalesperson(name, city, position, employmentStatus, status, sortOrder);
    }

    private static String scopedCity(TemporaryCheckinAdminPrincipal principal, String requestedCity) {
        if (!principal.allCities()) {
            if (requestedCity != null && !principal.city().equals(requestedCity)) {
                throw TemporaryCheckinException.adminForbidden("不能查看或维护其他城市的销售");
            }
            return principal.city();
        }
        return requestedCity;
    }

    private static String normalizeStatus(String raw, boolean optional) {
        if (raw == null || raw.isBlank()) return optional ? null : "ACTIVE";
        String status = raw.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw TemporaryCheckinException.badRequest("status仅支持ACTIVE或INACTIVE");
        }
        return status;
    }

    private static String required(String value, int maximum, String message) {
        String normalized = optional(value, maximum);
        if (normalized == null) throw TemporaryCheckinException.badRequest(message);
        return normalized;
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maximum || normalized.chars().anyMatch(Character::isISOControl)) {
            throw TemporaryCheckinException.badRequest("字段长度或字符无效");
        }
        return normalized;
    }

    private static AdminSalespersonView view(SalespersonAdminRow row) {
        return new AdminSalespersonView(row.id(), row.name(), row.city(), row.position(),
                row.employmentStatus(), row.status(), row.sortOrder(), row.credentialConfigured(),
                row.createdAt(), row.updatedAt());
    }

    private record NormalizedSalesperson(
            String name, String city, String position, String employmentStatus,
            String status, int sortOrder) { }
}
