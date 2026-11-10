package com.rigour.integration.application.service.huolala;

import com.rigour.integration.api.v1.model.HuolalaModels.*;
import com.rigour.integration.application.port.out.*;
import com.rigour.order.api.v1.model.LogisticsImportModels.ApiSource;
import com.rigour.shared.context.*;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** 授权及同步编排不创建成本事实；只有Order中的人工业务确认才会入账。 */
@Service
public class HuolalaService {
    private static final UUID SERVICE_ID = UUID.fromString("691faa79-2d5d-45c3-9868-34d079500001");
    private final HuolalaStore store;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.rigour.integration.application.port.out.HuolalaVouchers vouchers;

    private final HuolalaGateway gateway;
    private final HuolalaDomainClient domains;
    private final ObjectMapper json;
    private final TaskExecutor executor;
    private final DeploymentSettings deployment;

    /** 应用与企业绑定由部署环境管理，业务页面只维护同步偏好。 */
    public record DeploymentSettings(
            String enterpriseId,
            String enterpriseName,
            String secretRef,
            String redirectUri,
            Boolean sandbox) {}

    public HuolalaService(
            HuolalaStore store,
            HuolalaGateway gateway,
            HuolalaDomainClient domains,
            ObjectMapper json,
            @Qualifier("huolalaExecutor") TaskExecutor executor,
            DeploymentSettings deployment) {
        this.store = store;
        this.gateway = gateway;
        this.domains = domains;
        this.json = json;
        this.executor = executor;
        this.deployment = deployment;
    }

    private static CallerIdentity actor(String action) {
        AuthorizationContext.requirePermission("integration:huolala:" + action);
        var a = AuthorizationContext.requireCurrent();
        if (a.tenantId() == null || !"TENANT".equals(a.principalScope()))
            throw new AuthorizationDeniedException("tenant-user-caller");
        return a;
    }

    public Connection connection() {
        var a = actor("read");
        return store.connection(a.tenantId()).map(this::view).orElse(null);
    }

    private Connection view(Connection c) {
        return new Connection(
                c.id(),
                c.enterpriseId(),
                c.enterpriseName(),
                c.secretRef(),
                c.sandbox(),
                c.redirectUri(),
                c.enabled(),
                c.intervalMinutes(),
                c.startDate(),
                c.authStatus(),
                c.expiresAt(),
                gateway.ready(c),
                c.revision(),
                c.lastSuccessAt(),
                c.lastError());
    }

    public Connection save(SaveConnection c) {
        var a = actor("write");
        if (c == null) throw new IllegalArgumentException("请填写同步设置");
        var previous = store.connection(a.tenantId()).orElse(null);
        // 不接收浏览器传来的企业或应用配置，防止隐藏字段仍可更换来源企业。
        c =
                new SaveConnection(
                        configured(
                                deployment.enterpriseId(),
                                previous == null ? null : previous.enterpriseId()),
                        configured(
                                deployment.enterpriseName(),
                                previous == null ? null : previous.enterpriseName()),
                        configured(
                                deployment.secretRef(),
                                previous == null ? "env://RIGOUR_HUOLALA" : previous.secretRef()),
                        deployment.sandbox() == null
                                ? previous != null && previous.sandbox()
                                : deployment.sandbox(),
                        configured(
                                deployment.redirectUri(),
                                previous == null ? null : previous.redirectUri()),
                        c.enabled(),
                        c.intervalMinutes(),
                        c.startDate(),
                        c.revision());
        if (c.enterpriseId() == null || c.enterpriseName() == null || c.redirectUri() == null)
            throw new IllegalStateException("货拉拉企业接入尚未配置，请联系管理员完成服务端配置");
        if (c == null
                || c.enterpriseId() == null
                || !c.enterpriseId().matches("[0-9]{1,80}")
                || c.enterpriseName() == null
                || c.enterpriseName().isBlank()
                || c.enterpriseName().length() > 150
                || c.secretRef() == null
                || !c.secretRef().matches("env://[A-Za-z_][A-Za-z0-9_]{0,100}")
                || c.intervalMinutes() < 5
                || c.intervalMinutes() > 1440
                || c.startDate() == null
                || c.startDate().isAfter(today())
                || c.startDate().isBefore(today().minusYears(3)))
            throw new IllegalArgumentException("请填写有效企业、凭据引用、同步日期及5～1440分钟的同步频率");
        URI uri = URI.create(c.redirectUri() == null ? "" : c.redirectUri());
        boolean local =
                Set.of("localhost", "127.0.0.1", "[::1]")
                        .contains(Objects.toString(uri.getHost(), ""));
        if (uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !"/huolala-authorized.html".equals(uri.getPath())
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme())))
            throw new IllegalArgumentException("授权回调须为本站HTTPS地址/huolala-authorized.html；仅本机允许HTTP");
        return view(store.save(a.tenantId(), a.principalId(), c));
    }

    private static String configured(String configured, String previous) {
        return configured == null || configured.isBlank() ? previous : configured.trim();
    }

    public AuthorizationStart authorize() {
        var a = actor("write");
        var c = required(a.tenantId());
        if (!gateway.ready(c))
            throw new IllegalStateException("请先在Integration部署环境配置应用凭据、令牌加密密钥和私有持久化目录");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expires = Instant.now().plusSeconds(600);
        store.beginAuth(a.tenantId(), a.principalId(), sha(state), expires);
        return new AuthorizationStart(gateway.authorizeUrl(c), state, expires);
    }

    public Connection complete(AuthorizationFinish command) {
        var a = actor("write");
        if (command == null
                || command.credential() == null
                || command.credential().isBlank()
                || command.credential().length() > 2000
                || command.state() == null
                || command.state().length() > 100
                || !store.consumeAuth(
                        a.tenantId(), a.principalId(), sha(command.state()), Instant.now()))
            throw new IllegalArgumentException("授权流程已过期或不属于当前账号，请重新发起授权");
        var c = required(a.tenantId());
        var token = gateway.exchange(c, command.credential());
        // 先验证企业归属，避免将个人账号或另一家企业的授权写入当前连接。
        var proof = gateway.read(c, token, "e-order-list", Map.of("page_no", 1, "page_size", 1));
        for (var order : proof.path("order_arr"))
            if (!c.enterpriseId().equals(order.path("ep_id").asText()))
                throw new IllegalArgumentException("授权账号订单所属企业与配置不一致");
        gateway.save(a.tenantId().toString(), c.id(), token);
        store.authorized(a.tenantId(), token.expiresAt());
        return view(required(a.tenantId()));
    }

    public List<Employee> employees() {
        var a = actor("read");
        return store.employees(a.tenantId());
    }

    public List<EmployeeCandidate> candidates(String keyword) {
        var a = actor("write");
        if (keyword != null && keyword.length() > 100)
            throw new IllegalArgumentException("员工搜索条件过长");
        return domains.employees(a, keyword);
    }

    public List<Employee> bind(BindEmployee command) {
        var a = actor("write");
        if (command == null
                || command.staffId() == null
                || command.reason() == null
                || command.reason().isBlank()
                || command.reason().length() > 500)
            throw new IllegalArgumentException("请选择货拉拉员工并填写关联变更原因");
        var external =
                store.employee(a.tenantId(), command.staffId())
                        .orElseThrow(() -> new IllegalArgumentException("请先同步货拉拉员工目录"));
        var internal =
                command.employeeId() == null ? null : domains.employee(a, command.employeeId());
        if (internal != null
                && (internal.employeeCode() == null
                        || Set.of("LEFT", "RESIGNED", "INACTIVE")
                                .contains(Objects.toString(internal.employmentStatus(), ""))))
            throw new IllegalArgumentException("请选择有效的本系统员工");
        store.bind(
                a.tenantId(),
                a.principalId(),
                external,
                internal,
                command.revision(),
                command.reason().strip());
        publishEmployeeBindings();
        return store.employees(a.tenantId());
    }

    private final java.util.concurrent.atomic.AtomicBoolean bindingPublication =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Scheduled(fixedDelayString = "${rigour.integration.huolala.employee-binding-delay-ms:5000}")
    public void publishEmployeeBindings() {
        if (!bindingPublication.compareAndSet(false, true)) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            for (var job : store.bindingsDue(Instant.now())) {
                                if (!store.claimBinding(job, Instant.now())) continue;
                                boolean success = false;
                                try {
                                    domains.employeeMapping(service(job.tenant()), job.update());
                                    success = true;
                                } catch (RuntimeException ignored) {
                                    // 持久化任务保留，独立重试；不能将失败消息误报成已同步。
                                }
                                store.finishBinding(job, success, Instant.now());
                            }
                        } finally {
                            bindingPublication.set(false);
                        }
                    });
        } catch (RuntimeException rejected) {
            // 后台队列繁忙不阻塞人工保存，持久化事件会在下一轮继续投递。
            bindingPublication.set(false);
        }
    }

    public List<Run> runs() {
        return store.runs(actor("read").tenantId());
    }

    public List<SyncIssue> issues(String run) {
        return store.issues(actor("read").tenantId(), UUID.fromString(run));
    }

    public Run sync(SyncCommand command) {
        var a = actor("sync");
        return submit(a.tenantId(), a.principalId(), command, "MANUAL");
    }

    private Run submit(UUID tenant, UUID actor, SyncCommand command, String trigger) {
        var c = required(tenant);
        if (!"AUTHORIZED".equals(c.authStatus()))
            throw new IllegalStateException("货拉拉企业账号尚未授权或授权已失效");
        var q = validated(c, command, "SCHEDULED".equals(trigger) ? 366 : 93);
        UUID run = store.startRun(tenant, actor, q.scope(), trigger, Instant.now());
        if (!store.claim(tenant, run.toString(), Instant.now())) {
            store.finishRun(tenant, run, 0, 0, 0, 0, "已有同步或授权流程正在执行", Instant.now());
            throw new IllegalStateException("已有同步或授权流程正在执行，请稍后查看记录");
        }
        try {
            executor.execute(() -> execute(tenant, c, run, q));
        } catch (RuntimeException e) {
            store.finishRun(tenant, run, 0, 0, 0, 0, "任务队列繁忙，请稍后重试", Instant.now());
            store.finish(tenant, run.toString(), "任务队列繁忙", Instant.now());
            throw new IllegalStateException("任务队列繁忙，请稍后重试");
        }
        return new Run(run.toString(), q.scope(), "RUNNING", 0, 0, 0, 0, Instant.now(), null, null);
    }

    private SyncCommand validated(Connection c, SyncCommand command, int maxDays) {
        String scope = command == null || command.scope() == null ? "ALL" : command.scope();
        if (!Set.of("ALL", "ORDERS", "EMPLOYEES").contains(scope))
            throw new IllegalArgumentException("同步范围无效");
        LocalDate from =
                command == null || command.from() == null
                        ? max(c.startDate(), today().minusMonths(1).withDayOfMonth(1))
                        : command.from();
        LocalDate to = command == null || command.to() == null ? today() : command.to();
        String no = command == null ? null : command.orderNo();
        if (from.isAfter(to)
                || to.isAfter(today())
                || from.isBefore(today().minusYears(3))
                || from.plusDays(maxDays).isBefore(to)
                || no != null && !no.isBlank() && !no.matches("[0-9]{6,19}"))
            throw new IllegalArgumentException("同步日期须在近三年且单次不超过93天；订单号须为完整数字文本");
        return new SyncCommand(scope, from, to, no);
    }

    private void execute(UUID tenant, Connection c, UUID run, SyncCommand q) {
        long[] count = new long[4];
        String error = null;
        try {
            var token = gateway.load(tenant.toString(), c.id());
            if (!token.expiresAt().isAfter(Instant.now().plusSeconds(120))) {
                try {
                    token = gateway.refresh(c, token);
                    gateway.save(tenant.toString(), c.id(), token);
                    store.authorized(tenant, token.expiresAt());
                } catch (RuntimeException e) {
                    store.expired(tenant);
                    throw new IllegalStateException("货拉拉令牌刷新失败，请重新授权");
                }
            }
            if (!"ORDERS".equals(q.scope())) {
                // 员工接口能力独立于订单查询；目录暂不可用不应丢弃整批订单。
                if ("EMPLOYEES".equals(q.scope()))
                    token = syncEmployees(tenant, c, run, token, count);
                else {
                    try {
                        token = syncEmployees(tenant, c, run, token, count);
                    } catch (RuntimeException directoryFailure) {
                        count[3]++;
                        String raw =
                                store.raw(
                                        tenant,
                                        UUID.fromString(c.id()),
                                        run,
                                        "EMPLOYEE_DIRECTORY_FAILED",
                                        "DIRECTORY",
                                        json.createObjectNode());
                        store.delivery(
                                tenant, run, raw, "DIRECTORY", "员工目录暂未获取，订单继续同步；请检查员工查询权限后重试");
                        // 目录处理过程中可能已换过令牌；重新读取当前令牌再处理订单。
                        token = gateway.load(tenant.toString(), c.id());
                    }
                }
            }
            if (!"EMPLOYEES".equals(q.scope())) syncOrders(tenant, c, run, q, token, count);
        } catch (RuntimeException e) {
            error = safeError(e);
        } finally {
            store.finishRun(
                    tenant, run, count[0], count[1], count[2], count[3], error, Instant.now());
            store.finish(
                    tenant,
                    run.toString(),
                    error == null && count[3] > 0 ? "部分来源未成功，请查看同步记录并补同步" : error,
                    Instant.now());
        }
    }

    private HuolalaGateway.Token syncEmployees(
            UUID tenant, Connection c, UUID run, HuolalaGateway.Token token, long[] count) {
        var tree = gateway.read(c, token, "e-staff-construct", Map.of());
        List<JsonNode> staff = new ArrayList<>();
        collect(tree, staff, 0);
        if (staff.size() > 5000) throw new IllegalStateException("员工目录超过单次处理上限，请联系管理员");
        for (var node : staff) {
            store.heartbeat(tenant, run.toString(), Instant.now());
            count[0]++;
            token = renew(tenant, c, token);
            String rawId = null, id = node.path("code").asText();
            try {
                if (!id.matches("[0-9]{1,10}")) throw new IllegalArgumentException("平台员工编号无效");
                var employee =
                        gateway.read(
                                c, token, "e-staff-detail", Map.of("staff_id", Long.parseLong(id)));
                if (employee.path("staff_id").asText("").isBlank()
                        || !c.enterpriseId().equals(employee.path("ep_id").asText()))
                    throw new IllegalArgumentException("员工所属企业不一致");
                rawId = store.raw(tenant, UUID.fromString(c.id()), run, "EMPLOYEE", id, employee);
                store.delivery(tenant, run, rawId, id, null);
                count[1]++;
            } catch (RuntimeException e) {
                count[3]++;
                if (rawId == null)
                    rawId =
                            store.raw(
                                    tenant,
                                    UUID.fromString(c.id()),
                                    run,
                                    "EMPLOYEE_FAILED",
                                    id,
                                    node);
                store.delivery(tenant, run, rawId, id, safeError(e));
            }
        }
        return token;
    }

    private void syncOrders(
            UUID tenant,
            Connection c,
            UUID run,
            SyncCommand q,
            HuolalaGateway.Token token,
            long[] count) {
        var staff = store.employees(tenant);
        Map<String, Long> mappingRevisions = new HashMap<>();
        Map<String, String> names = new HashMap<>(),
                mappings = new HashMap<>(),
                phones = new HashMap<>(),
                staffIds = new HashMap<>();
        for (var e : staff)
            if (!e.userId().isBlank()) {
                mappingRevisions.put(e.userId(), e.revision());
                names.put(e.userId(), e.name());
                phones.put(e.userId(), e.phone());
                staffIds.put(e.userId(), e.staffId());
                if (e.employeeCode() != null) mappings.put(e.userId(), e.employeeCode());
            }
        for (int page = 1; page <= 500; page++) {
            store.heartbeat(tenant, run.toString(), Instant.now());
            token = renew(tenant, c, token);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("page_no", page);
            parameters.put("page_size", 20);
            if (q.orderNo() != null && !q.orderNo().isBlank())
                parameters.put("order_display_id", Long.parseLong(q.orderNo()));
            else {
                parameters.put(
                        "order_start_datetime",
                        q.from().atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond());
                parameters.put(
                        "order_end_datetime",
                        q.to().plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond()
                                - 1);
            }
            var data = gateway.read(c, token, "e-order-list", parameters);
            var orders = data.path("order_arr");
            if (!orders.isArray()) throw new IllegalStateException("货拉拉订单列表响应结构异常");
            for (var source : orders) {
                count[0]++;
                store.heartbeat(tenant, run.toString(), Instant.now());
                token = renew(tenant, c, token);
                String rawId = null, no = source.path("order_display_id").asText("");
                try {
                    String user = source.path("user_id").asText("");
                    var listPayload = json.createObjectNode();
                    listPayload.set("list", source);
                    rawId =
                            store.raw(
                                    tenant,
                                    UUID.fromString(c.id()),
                                    run,
                                    "ORDER_LIST",
                                    no,
                                    listPayload);
                    var summary =
                            HuolalaOrderNormalizer.normalize(
                                    c,
                                    source,
                                    json.createObjectNode(),
                                    rawId,
                                    mappings.get(user),
                                    names.get(user),
                                    staffIds.get(user),
                                    phones.get(user));
                    summary = withMappingRevision(summary, mappingRevisions.getOrDefault(user, 0L));
                    var row =
                            domains.receive(
                                    service(tenant),
                                    new ApiSource(
                                            c.id(),
                                            HuolalaOrderNormalizer.fingerprint(summary, json),
                                            summary));
                    boolean detailComplete = false;
                    try {
                        var detail =
                                gateway.read(
                                        c,
                                        token,
                                        "e-order-detail",
                                        Map.of("order_display_id", Long.parseLong(no)));
                        var payload = json.createObjectNode();
                        payload.set("list", source);
                        payload.set("detail", detail);
                        rawId =
                                store.raw(
                                        tenant, UUID.fromString(c.id()), run, "ORDER", no, payload);
                        var normalized =
                                HuolalaOrderNormalizer.normalize(
                                        c,
                                        source,
                                        detail,
                                        rawId,
                                        mappings.get(user),
                                        names.get(user),
                                        staffIds.get(user),
                                        phones.get(user));
                        normalized =
                                withMappingRevision(
                                        normalized, mappingRevisions.getOrDefault(user, 0L));
                        if (vouchers != null) vouchers.enqueue(tenant, c.id(), normalized, detail);
                        var detailedRow =
                                domains.receive(
                                        service(tenant),
                                        new ApiSource(
                                                c.id(),
                                                HuolalaOrderNormalizer.fingerprint(
                                                        normalized, json),
                                                normalized));
                        if (!"CREATED".equals(row.syncOutcome())) row = detailedRow;
                        detailComplete = true;
                    } catch (RuntimeException detailFailure) {
                        count[3]++;
                        store.delivery(tenant, run, rawId, no, "订单列表已保存，详情暂未同步，请重试");
                    }
                    if (detailComplete) store.delivery(tenant, run, rawId, no, null);
                    if ("UNCHANGED".equals(row.syncOutcome())) count[2]++;
                    else count[1]++;
                } catch (RuntimeException e) {
                    count[3]++;
                    if (rawId == null)
                        rawId =
                                store.raw(
                                        tenant,
                                        UUID.fromString(c.id()),
                                        run,
                                        "ORDER_FAILED",
                                        no.isBlank() ? "UNKNOWN" : no,
                                        source);
                    store.delivery(tenant, run, rawId, no, safeError(e));
                }
            }
            store.progress(
                    tenant, run, count[0], count[1], count[2], count[3], q.from(), q.to(), page);
            if (orders.size() < 20 || page * 20L >= data.path("total").asLong(Long.MAX_VALUE))
                return;
        }
        throw new IllegalStateException("订单超过单次10000条上限，请缩小时间范围后补同步");
    }

    private com.rigour.order.api.v1.model.LogisticsImportModels.CarrierOrder withMappingRevision(
            com.rigour.order.api.v1.model.LogisticsImportModels.CarrierOrder order, long revision) {
        var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(order);
        ((tools.jackson.databind.node.ObjectNode) node.path("facts"))
                .put("employeeMappingRevision", revision);
        return json.treeToValue(
                node, com.rigour.order.api.v1.model.LogisticsImportModels.CarrierOrder.class);
    }

    private HuolalaGateway.Token renew(UUID tenant, Connection c, HuolalaGateway.Token token) {
        if (token.expiresAt().isAfter(Instant.now().plusSeconds(120))) return token;
        try {
            var refreshed = gateway.refresh(c, token);
            gateway.save(tenant.toString(), c.id(), refreshed);
            store.authorized(tenant, refreshed.expiresAt());
            return refreshed;
        } catch (RuntimeException e) {
            store.expired(tenant);
            throw new IllegalStateException("货拉拉令牌刷新失败，请重新授权");
        }
    }

    private static void collect(JsonNode node, List<JsonNode> result, int depth) {
        if (depth > 40) throw new IllegalStateException("员工目录层级异常");
        if (node.isArray()) {
            for (var child : node) collect(child, result, depth + 1);
        } else if (node.isObject()) {
            if (node.path("flag").asInt() == 2) result.add(node);
            else {
                if (node.has("list")) collect(node.path("list"), result, depth + 1);
                if (node.has("children")) collect(node.path("children"), result, depth + 1);
            }
        }
    }

    @Scheduled(fixedDelayString = "${rigour.integration.huolala.poll-delay-ms:60000}")
    public void tick() {
        for (var tenant : store.due(Instant.now()))
            try {
                var c = required(tenant);
                boolean daily = store.historyDue(tenant, today());
                var from =
                        max(
                                c.startDate(),
                                daily
                                        ? today().minusYears(1).plusDays(1)
                                        : today().minusMonths(1).withDayOfMonth(1));
                // 官方未提供修改时间增量：日常扫描本月及上月，每日重查近一年，覆盖旧单退款和调账。
                var to = today();
                submit(
                        tenant,
                        SERVICE_ID,
                        new SyncCommand(daily ? "ALL" : "ORDERS", from, to, null),
                        "SCHEDULED");
            } catch (RuntimeException ignored) {
                /* 任务失败由持久化批次记录，下一轮按租约重试。 */
            }
    }

    private Connection required(UUID tenant) {
        return store.connection(tenant)
                .orElseThrow(() -> new IllegalStateException("请先保存货拉拉企业连接配置"));
    }

    private static CallerIdentity service(UUID tenant) {
        return new CallerIdentity(
                "SERVICE",
                SERVICE_ID,
                tenant,
                null,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of("INTEGRATION_HUOLALA"),
                Set.of("integration:huolala:sync"));
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static String sha(String v) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("授权摘要不可用");
        }
    }

    private static String safeError(RuntimeException e) {
        String value =
                (e instanceof IllegalArgumentException || e instanceof IllegalStateException)
                        ? e.getMessage()
                        : "业务接口或网络处理失败，请补同步核对";
        return value == null ? "同步未成功" : value.substring(0, Math.min(480, value.length()));
    }
}
