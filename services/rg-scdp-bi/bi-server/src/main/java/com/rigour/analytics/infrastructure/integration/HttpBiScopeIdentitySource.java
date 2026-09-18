package com.rigour.analytics.infrastructure.integration;

import com.rigour.analytics.api.v1.model.BiScopeSyncCommand;
import com.rigour.analytics.application.port.out.BiDataScopeStore.Grant;
import com.rigour.analytics.application.port.out.BiScopeIdentitySource;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/** IAM 身份与策略校验后，以最小目录服务身份核验 HR/CRM；不按姓名建立关联。 */
@Component
public final class HttpBiScopeIdentitySource implements BiScopeIdentitySource {
    private final RestClient client;
    private final TrustedContextSigner signer;
    private final String iam;
    private final String hr;
    private final String crm;

    @Autowired
    public HttpBiScopeIdentitySource(TrustedContextSigner signer,
            @Value("${rigour.analytics.scope.iam-base-url:http://localhost:26881}") String iam,
            @Value("${rigour.analytics.scope.hr-base-url:http://localhost:26889}") String hr,
            @Value("${rigour.analytics.scope.crm-base-url:http://localhost:26883}") String crm) {
        this(defaultBuilder(), signer, iam, hr, crm);
    }

    HttpBiScopeIdentitySource(RestClient.Builder builder, TrustedContextSigner signer, String iam, String hr, String crm) {
        this.client = builder.build();
        this.signer = signer;
        this.iam = base(iam); this.hr = base(hr); this.crm = base(crm);
    }

    private static RestClient.Builder defaultBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory);
    }

    @Override public VerifiedIdentity verify(CallerIdentity actor, BiScopeSyncCommand command) {
        String userId = command.userId().toString();
        // IAM 管理 API 使用 JWT，先确认原始 token 与已签名调用人属于同一个租户/账号。
        var me = get(actor, iam, "/api/v1/me", Map.of(), true);
        require(actor.tenantId().toString().equals(text(me, "tenantId"))
                && actor.userId().toString().equals(text(me, "id")));
        var staff = get(actor, iam, "/api/v1/iam/bi-identity", Map.of("userId", userId), true);
        require(userId.equals(text(staff, "userId")) && staff.path("userSecurityVersion").isNumber()
                && staff.path("tenantPolicyVersion").asLong(-1) == actor.tenantPolicyVersion());
        var employee = resolveHrEmployee(actor, staff);
        String employeeCode = required(employee, "employeeCode");
        require("ACTIVE".equals(text(employee, "employmentStatus")));

        var customerPage = get(actor, crm, "/api/v1/crm/internal-customers",
                Map.of("ownerEmployeeCode", employeeCode, "begin", "0", "step", "2", "statusCode", "ACTIVE"), false);
        require(customerPage.path("total").isNumber() && customerPage.path("total").asLong() >= 0);
        require(rows(customerPage.path("items")).stream().allMatch(row -> employeeCode.equals(text(row, "ownerEmployeeCode"))));
        // 员工身份由 IAM + HR 证明；CRM 没有客户是合法空态，不能伪造客户记录引用。
        String crmRef = "crm:v1:ownerEmployeeCode-contract";
        var policies = rows(staff.path("policies"));
        var grants = new ArrayList<Grant>();
        for (var policyId : command.iamPolicyIds().stream().distinct().toList()) {
            var policy = one(policies, "id", policyId.toString());
            String type = required(policy, "scopeType");
            require(List.of("SELF", "MY_CITY", "MY_REGION").contains(type));
            for (String region : command.regionCodes().stream().distinct().toList()) {
                // 城市来自真实 CRM 字典，管理员通过已有 IAM 策略明确选择，不从城市名称推断授权。
                var areas = get(actor, crm, "/api/v1/crm/customer-areas", Map.of("q", region, "begin", "0", "step", "100"), false);
                var area = one(rows(areas.path("items")), "code", region);
                require("ACTIVE".equals(text(area, "status")));
                grants.add(new Grant(required(policy, "roleCode"), type, region, policyId.toString()));
            }
        }
        return new VerifiedIdentity(employeeCode, employeeCode, "staff:" + required(staff, "staffId") + ":user:" + userId,
                "employee:" + required(employee, "id"), crmRef, staff.path("userSecurityVersion").asLong(), List.copyOf(grants));
    }

    private JsonNode get(CallerIdentity actor, String base, String path, Map<String, String> query, boolean iamRequest) {
        var builder = UriComponentsBuilder.fromUriString(base).path(path);
        query.forEach(builder::queryParam);
        URI uri = builder.build().encode().toUri();
        var request = client.get().uri(uri);
        if (iamRequest) {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) throw new IllegalStateException("Missing administrator request");
            String token = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            require(token != null && token.startsWith("Bearer "));
            request.header(HttpHeaders.AUTHORIZATION, token);
        } else request.headers(headers -> signed(directoryCaller(actor), uri).forEach(headers::set));
        JsonNode result = request.retrieve().body(JsonNode.class);
        require(result != null);
        if (result.has("code")) {
            require("OK".equals(text(result, "code")));
            result = result.path("data");
        }
        return result;
    }

    private JsonNode resolveHrEmployee(CallerIdentity actor, JsonNode staff) {
        String code = required(staff, "staffCode");
        var page = get(actor, hr, "/api/v1/hr/employees", Map.of("employeeCode", code, "begin", "0", "step", "2"), false);
        long total = page.path("total").asLong(-1);
        if (total == 1) return one(rows(page.path("items")), "employeeCode", code);
        require(total == 0);
        // IAM/HR 历史编码不同，只允许通过双方已保存的同源外部 ID 精确关联。
        var resolved = new ArrayList<JsonNode>();
        for (var binding : rows(staff.path("externalBindings"))) {
            URI uri = UriComponentsBuilder.fromUriString(hr).path("/internal/v1/hr/employees/source-resolve").build().toUri();
            var result = client.post().uri(uri).headers(headers -> signed(directoryCaller(actor), uri, "POST").forEach(headers::set))
                    .body(Map.of("sourceSystem", required(binding, "sourceSystem"),
                            "sourceTenantKey", required(binding, "sourceTenantKey"),
                            "sourceEmployeeIds", List.of(required(binding, "sourceEmployeeId")), "employeeNames", List.of()))
                    .retrieve().body(JsonNode.class);
            require(result != null && "OK".equals(text(result, "code")));
            for (var item : rows(result.path("data"))) {
                require(required(binding, "sourceSystem").equals(text(item, "sourceSystem"))
                        && required(binding, "sourceTenantKey").equals(text(item, "sourceTenantKey"))
                        && required(binding, "sourceEmployeeId").equals(text(item, "sourceEmployeeId")));
                resolved.add(item);
            }
        }
        var ids = resolved.stream().map(item -> required(item, "employeeId")).distinct().toList();
        require(ids.size() == 1);
        var employee = get(actor, hr, "/api/v1/hr/employees/" + ids.getFirst(), Map.of(), false);
        require(ids.getFirst().equals(text(employee, "id")));
        return employee;
    }

    private Map<String, String> signed(CallerIdentity caller, URI uri) {
        return signed(caller, uri, "GET");
    }
    private Map<String, String> signed(CallerIdentity caller, URI uri, String method) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        headers.put(RequestHeaders.PRINCIPAL_ID, caller.principalId().toString());
        headers.put(RequestHeaders.TENANT_ID, caller.tenantId().toString());
        if (caller.userId() != null) headers.put(RequestHeaders.USER_ID, caller.userId().toString());
        headers.put(RequestHeaders.SESSION_ID, caller.sessionId().toString());
        headers.put(RequestHeaders.SESSION_VERSION, Long.toString(caller.sessionVersion()));
        headers.put(RequestHeaders.USER_SECURITY_VERSION, Long.toString(caller.userSecurityVersion()));
        headers.put(RequestHeaders.TENANT_POLICY_VERSION, Long.toString(caller.tenantPolicyVersion()));
        headers.put(RequestHeaders.ROLES, String.join(",", caller.roles()));
        headers.put(RequestHeaders.PERMISSIONS, String.join(",", caller.permissions()));
        var signed = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }
    private static JsonNode one(List<JsonNode> rows, String field, String expected) {
        var matches = rows.stream().filter(row -> expected.equals(text(row, field))).toList();
        require(matches.size() == 1);
        return matches.getFirst();
    }
    /** 仅用于已通过 IAM 身份/策略核验后的目录读取，不赋予登录用户 HR/CRM 权限。 */
    private static CallerIdentity directoryCaller(CallerIdentity actor) {
        var id = java.util.UUID.nameUUIDFromBytes("rigour-analytics-bi-scope-directory".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new CallerIdentity("SERVICE", id, actor.tenantId(), null, null, java.util.UUID.randomUUID(),
                0, 0, 0, java.util.Set.of(), java.util.Set.of("hr:employee:read", "crm:customer:read"));
    }
    private static List<JsonNode> rows(JsonNode node) {
        require(node != null && node.isArray());
        var rows = new ArrayList<JsonNode>(); node.forEach(rows::add); return rows;
    }
    private static String text(JsonNode node, String field) { return node.path(field).asText(null); }
    private static String required(JsonNode node, String field) {
        String value = text(node, field); require(value != null && !value.isBlank()); return value;
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalStateException("Authoritative scope evidence unavailable or ambiguous"); }
    private static String base(String base) {
        URI uri = URI.create(Objects.requireNonNull(base));
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("Invalid identity source base URL");
        return base.replaceAll("/+$", "");
    }
}
