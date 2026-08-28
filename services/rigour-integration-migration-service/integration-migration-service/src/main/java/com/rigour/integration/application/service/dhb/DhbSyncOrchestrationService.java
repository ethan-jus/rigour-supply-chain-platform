package com.rigour.integration.application.service.dhb;

import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationCommand;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationResult;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationStepView;
import com.rigour.integration.api.v1.model.DhbSyncOrchestrationTenantView;
import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.PageRequest;
import com.rigour.integration.application.port.out.DhbClient.Staff;
import com.rigour.integration.application.port.out.DhbClient.StaffQuery;
import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.DhbIntegrationStore.RawLanding;
import com.rigour.integration.application.port.out.DhbOrchestrationLease;
import com.rigour.integration.application.port.out.ErpDhbDomainSyncClient;
import com.rigour.integration.application.port.out.IamDhbStaffSyncClient;
import com.rigour.integration.application.port.out.IamDhbStaffSyncClient.DhbStaffRow;
import com.rigour.integration.application.port.out.IamDhbStaffSyncClient.StaffSyncResult;
import com.rigour.merchant.api.v1.model.SyncObjectResult;
import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.MappingIssue;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import com.rigour.shared.core.sync.SyncConflictClassifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** 订货宝统一同步编排器；按新业务表依赖顺序调用各领域同步能力。 */
public final class DhbSyncOrchestrationService {
    private static final Logger log = LoggerFactory.getLogger(DhbSyncOrchestrationService.class);
    private static final String ORCHESTRATION_LEASE_OWNER =
            "rigour-integration-migration-service:DHB_ORCHESTRATION";
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "service:rigour-dhb-sync-orchestrator".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> SERVICE_PERMISSIONS = Set.of(
            "integration:dhb:read", "integration:dhb:write", "integration:dhb:sync-discovery",
            "business-settings:dict:sync", "iam:staff:read", "iam:staff:sync");
    private static final List<String> PRODUCT_OBJECTS = List.of(
            "CATEGORY", "BRAND", "SPECIFICATION", "TAG", "PRODUCT_SPU");
    private static final List<String> SUPPLY_OBJECTS = List.of(
            "SUPPLIER", "WAREHOUSE", "PURCHASE_ORDER", "PURCHASE_RETURN",
            "WAREHOUSING_RECEIPT", "INVENTORY");
    private static final List<Observation> BASELINE_DICTIONARY_OBSERVATIONS = List.of(
            new Observation("PRODUCT_UNIT", "unit.internalCode", "BOX", "箱"),
            new Observation("PRODUCT_UNIT", "unit.internalCode", "BUCKET", "桶"),
            new Observation("PRODUCT_UNIT", "unit.internalCode", "PORTION", "份"),
            new Observation("PRODUCT_UNIT", "unit.internalCode", "SET", "套"),
            new Observation("PRODUCT_UNIT", "unit.internalCode", "BED", "床"),
            new Observation("PRODUCT_UNIT", "unit.internalCode", "PAIR", "副"),
            new Observation("PRODUCT_UNIT", "unit.internalCode", "BOTTLE", "瓶"),
            new Observation("PRODUCT_UNIT", "unit.internalCode", "STRIP", "条"),
            new Observation("PRODUCT_UNIT", "unit.internalCode", "GRAIN", "颗"),
            new Observation("PRODUCT_UNIT", "unit.internalCode", "PIECE", "件"),
            new Observation("DHB_UNIT", "sourceUnit.name", "箱", "箱"),
            new Observation("DHB_UNIT", "sourceUnit.name", "桶", "桶"),
            new Observation("DHB_UNIT", "sourceUnit.name", "份", "份"),
            new Observation("DHB_UNIT", "sourceUnit.name", "套", "套"),
            new Observation("DHB_UNIT", "sourceUnit.name", "床", "床"),
            new Observation("DHB_UNIT", "sourceUnit.name", "副", "副"),
            new Observation("DHB_UNIT", "sourceUnit.name", "瓶", "瓶"),
            new Observation("DHB_UNIT", "sourceUnit.name", "条", "条"),
            new Observation("DHB_UNIT", "sourceUnit.name", "颗", "颗"),
            new Observation("DHB_UNIT", "sourceUnit.name", "件", "件"),
            new Observation("DHB_PRODUCT_STATUS", "product.status", "T", "正常"),
            new Observation("DHB_PRODUCT_STATUS", "product.status", "F", "回收站"),
            new Observation("DHB_PRODUCT_PUTAWAY", "product.putaway", "T", "上架"),
            new Observation("DHB_PRODUCT_PUTAWAY", "product.putaway", "F", "下架"),
            new Observation("DHB_PURCHASE_ORDER_STATUS", "purchaseOrder.status", "pending", "待审核"),
            new Observation("DHB_PURCHASE_ORDER_STATUS", "purchaseOrder.status", "wh_up", "待入库"),
            new Observation("DHB_PURCHASE_ORDER_STATUS", "purchaseOrder.status", "wh_half", "部分入库"),
            new Observation("DHB_PURCHASE_ORDER_STATUS", "purchaseOrder.status", "cancelled", "已取消"),
            new Observation("DHB_PURCHASE_ORDER_STATUS", "purchaseOrder.status", "finished", "已完成"),
            new Observation("DHB_PURCHASE_PAYMENT_STATUS", "purchaseOrder.paymentStatus", "oblig", "待付款"),
            new Observation("DHB_PURCHASE_PAYMENT_STATUS", "purchaseOrder.paymentStatus", "uncollect", "部分付款"),
            new Observation("DHB_PURCHASE_PAYMENT_STATUS", "purchaseOrder.paymentStatus", "paided", "已付款"),
            new Observation("DHB_PURCHASE_RETURN_STATUS", "purchaseReturn.status", "stock_up", "待出库"),
            new Observation("DHB_PURCHASE_RETURN_STATUS", "purchaseReturn.status", "cancelled", "已取消"),
            new Observation("DHB_PURCHASE_RETURN_STATUS", "purchaseReturn.status", "refunds", "待退款"),
            new Observation("DHB_PURCHASE_RETURN_STATUS", "purchaseReturn.status", "finished", "已完成"),
            new Observation("DHB_WAREHOUSE_STATUS", "warehouse.status", "T", "正常"),
            new Observation("DHB_WAREHOUSE_STATUS", "warehouse.status", "F", "停用"),
            new Observation("DHB_CUSTOMER_STATUS", "customer.status", "T", "正常"),
            new Observation("DHB_CUSTOMER_STATUS", "customer.status", "F", "停用"),
            new Observation("DHB_CUSTOMER_STATUS", "customer.status", "A", "待激活"),
            new Observation("DHB_CUSTOMER_STATUS", "customer.status", "C", "待审核"),
            new Observation("DHB_CUSTOMER_CLEARING_FORM", "customer.clearingForm", "prepaid", "预付"),
            new Observation("DHB_CUSTOMER_CLEARING_FORM", "customer.clearingForm", "forward", "现付"),
            new Observation("DHB_CUSTOMER_CLEARING_FORM", "customer.clearingForm", "postpaid", "后付"),
            new Observation("DHB_FINANCIAL_BUSINESS_TYPE", "fund.incexpId", "1", "普通充值"),
            new Observation("DHB_FINANCIAL_BUSINESS_TYPE", "fund.incexpId", "19", "预付款充值"),
            new Observation("DHB_FINANCIAL_BUSINESS_TYPE", "fund.incexpId", "13", "订单收款"),
            new Observation("DHB_FINANCIAL_BUSINESS_TYPE", "fund.incexpId", "8", "期初充值"),
            new Observation("DHB_FINANCIAL_BUSINESS_TYPE", "fund.incexpId", "2", "退货退款"),
            new Observation("DHB_FINANCIAL_BUSINESS_TYPE", "fund.incexpId", "10", "退款失败回冲"),
            new Observation("DHB_FINANCIAL_BUSINESS_TYPE", "fund.incexpId", "9", "退款红冲"),
            new Observation("DHB_FINANCIAL_BUSINESS_TYPE", "fund.incexpId", "5", "预存款扣款"),
            new Observation("DHB_PAYMENT_METHOD", "fund.typeId", "Offline", "转账支付"),
            new Observation("DHB_PAYMENT_METHOD", "fund.typeId", "Deposit", "预存款支付"),
            new Observation("DHB_PAYMENT_METHOD", "fund.typeId", "Alipay", "支付宝支付"),
            new Observation("DHB_PAYMENT_METHOD", "fund.typeId", "Micro", "微信支付"),
            new Observation("DHB_PAYMENT_METHOD", "fund.typeId", "Quick", "快捷支付"));

    private final DhbIntegrationStore store;
    private final ErpDhbDomainSyncClient erpClient;
    private final CrmDhbDomainSyncClient crmClient;
    private final IamDhbStaffSyncClient iamClient;
    private final DhbClient dhbClient;
    private final DhbOrderSyncService orderSyncService;
    private final BusinessDictionaryBatchClient dictionaryClient;
    private final DhbOrchestrationLease orchestrationLease;
    private final DhbSyncOrchestrationProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<TenantConnector, ReentrantLock> tenantConnectorLocks = new ConcurrentHashMap<>();

    public DhbSyncOrchestrationService(DhbIntegrationStore store,
                                       ErpDhbDomainSyncClient erpClient,
                                       CrmDhbDomainSyncClient crmClient,
                                       IamDhbStaffSyncClient iamClient,
                                       DhbClient dhbClient,
                                       DhbOrderSyncService orderSyncService,
                                       BusinessDictionaryBatchClient dictionaryClient,
                                       DhbOrchestrationLease orchestrationLease,
                                       DhbSyncOrchestrationProperties properties,
                                       Clock clock,
                                       ObjectMapper objectMapper) {
        this.store = store;
        this.erpClient = erpClient;
        this.crmClient = crmClient;
        this.iamClient = iamClient;
        this.dhbClient = dhbClient;
        this.orderSyncService = orderSyncService;
        this.dictionaryClient = dictionaryClient;
        this.orchestrationLease = orchestrationLease;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public DhbSyncOrchestrationResult runScheduled() {
        properties.validate();
        return run(null, "SCHEDULED", null, properties.getMaxPages(),
                true, true, true, true, true, true);
    }

    public DhbSyncOrchestrationResult runManual(CallerIdentity caller,
                                                DhbSyncOrchestrationCommand command) {
        requireManualCaller(caller);
        int maxPages = maxPages(command == null ? null : command.maxPages());
        boolean includeErp = enabled(command == null ? null : command.includeErp());
        boolean includeErpProduct = enabled(command == null ? null : command.includeErpProduct(), includeErp);
        boolean includeErpSupply = enabled(command == null ? null : command.includeErpSupply(), includeErp);
        boolean includeCrm = enabled(command == null ? null : command.includeCrm());
        boolean includeOrder = enabled(command == null ? null : command.includeOrder());
        boolean includeIam = enabled(command == null ? null : command.includeIam());
        boolean includeDictionary = enabled(command == null ? null : command.includeDictionary());
        return run(caller.tenantId(), "MANUAL", caller.principalId(), maxPages,
                includeErpProduct, includeErpSupply, includeCrm, includeOrder, includeIam, includeDictionary);
    }

    private DhbSyncOrchestrationResult run(UUID tenantFilter, String triggerType, UUID actorId,
                                           int maxPages, boolean includeErpProduct,
                                           boolean includeErpSupply, boolean includeCrm,
                                           boolean includeOrder, boolean includeIam,
                                           boolean includeDictionary) {
        UUID batchId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        Map<TenantConnector, TargetBucket> targets = targets(tenantFilter);
        List<DhbSyncOrchestrationTenantView> tenantResults = new ArrayList<>();
        for (TargetBucket bucket : targets.values()) {
            tenantResults.add(runTenant(bucket, maxPages, includeErpProduct, includeErpSupply,
                    includeCrm, includeOrder, includeIam, includeDictionary));
        }
        String status = aggregateTenantStatus(tenantResults);
        DhbSyncOrchestrationResult result = new DhbSyncOrchestrationResult(
                batchId, status, triggerType, startedAt, clock.instant(), tenantResults);
        logStepFailures(batchId, tenantResults);
        log.info("订货宝统一同步编排完成 batchId={} triggerType={} tenantCount={} status={}",
                batchId, triggerType, tenantResults.size(), status);
        return result;
    }

    private DhbSyncOrchestrationTenantView runTenant(TargetBucket bucket, int maxPages,
                                                     boolean includeErpProduct, boolean includeErpSupply,
                                                     boolean includeCrm, boolean includeOrder,
                                                     boolean includeIam,
                                                     boolean includeDictionary) {
        ReentrantLock lock = tenantConnectorLocks.computeIfAbsent(bucket.key, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return new DhbSyncOrchestrationTenantView(bucket.key.tenantId(), bucket.key.connectorId(),
                    "SKIPPED", List.of(skipped("INTEGRATION", "DHB_ORCHESTRATION",
                    "当前租户订货宝连接器已有统一同步编排批次运行中")));
        }
        try {
            return orchestrationLease.execute(bucket.key.tenantId(), bucket.key.connectorId(),
                    ORCHESTRATION_LEASE_OWNER,
                    () -> runTenantUnderLock(bucket, maxPages, includeErpProduct, includeErpSupply,
                            includeCrm, includeOrder, includeIam, includeDictionary));
        } catch (RuntimeException error) {
            if (SyncConflictClassifier.isAlreadyRunning(error)) {
                return new DhbSyncOrchestrationTenantView(bucket.key.tenantId(), bucket.key.connectorId(),
                        "SKIPPED", List.of(skipped("INTEGRATION", "DHB_ORCHESTRATION",
                        "当前租户订货宝连接器已有统一同步编排批次运行中")));
            }
            throw error;
        } finally {
            lock.unlock();
        }
    }

    private DhbSyncOrchestrationTenantView runTenantUnderLock(TargetBucket bucket, int maxPages,
                                                             boolean includeErpProduct,
                                                             boolean includeErpSupply,
                                                             boolean includeCrm,
                                                             boolean includeOrder,
                                                             boolean includeIam,
                                                             boolean includeDictionary) {
        List<DhbSyncOrchestrationStepView> steps = new ArrayList<>();
        CallerIdentity caller = serviceCaller(bucket.key.tenantId());
        boolean failed = false;
        if (includeDictionary) {
            failed = runDictionaryStep(bucket, steps);
        }
        if (!failed && includeIam) {
            failed = runIamStaffStep(bucket, caller, maxPages, steps);
        }
        if (!failed && includeErpProduct) {
            failed = runErpSteps(bucket, caller, maxPages, steps);
        }
        if (!failed && includeCrm) {
            failed = runCrmStep(bucket, caller, maxPages, steps);
        }
        if (!failed && includeErpSupply) {
            failed = runSupplySteps(bucket, caller, maxPages, steps);
        }
        if (!failed && includeOrder) {
            runOrderStep(bucket, caller, maxPages, steps);
        }
        return new DhbSyncOrchestrationTenantView(bucket.key.tenantId(),
                bucket.key.connectorId(), aggregateStepStatus(steps), steps);
    }

    private boolean runDictionaryStep(TargetBucket bucket,
                                      List<DhbSyncOrchestrationStepView> steps) {
        if (bucket.dictionaryTarget == null) {
            steps.add(skipped("DICTIONARY", "BUSINESS_DICTIONARY", "未配置启用的业务字典同步任务"));
            return false;
        }
        Audit audit = dictionaryClient.sync(BusinessDictionaryBatchClient.serviceCaller(
                        "rigour-integration-migration-service", "DHB_DICTIONARY_BOOTSTRAP",
                        bucket.key.tenantId()),
                "DHB_ORCHESTRATION_BASELINE", BASELINE_DICTIONARY_OBSERVATIONS);
        if (audit == null) audit = Audit.empty();
        String status = audit.unmapped() == 0 ? "SUCCEEDED" : "SUCCEEDED_WITH_WARNINGS";
        steps.add(new DhbSyncOrchestrationStepView("DICTIONARY", "BUSINESS_DICTIONARY",
                status, null, BASELINE_DICTIONARY_OBSERVATIONS.size(), audit.revisions().size(),
                audit.unmapped(), audit.revisions(), dictionaryMessage(audit)));
        return false;
    }

    private boolean runIamStaffStep(TargetBucket bucket, CallerIdentity caller, int maxPages,
                                    List<DhbSyncOrchestrationStepView> steps) {
        try {
            DhbClient.Connector connector = connector(bucket);
            PageRequest request = PageRequest.first(1_000);
            long fetched = 0;
            int created = 0;
            int updated = 0;
            int unchanged = 0;
            int failed = 0;
            List<String> failures = new ArrayList<>();
            for (int pageNo = 0; pageNo < maxPages; pageNo++) {
                Page<Staff> page = dhbClient.getStaff(connector,
                        new StaffQuery(request, null, null, null, null, null));
                List<Staff> items = page.items();
                fetched += items.size();
                List<RawLanding> raw = new ArrayList<>();
                List<DhbStaffRow> rows = new ArrayList<>();
                String sourceTenantKey = sourceTenantKey(bucket);
                for (Staff item : items) {
                    String sourceStaffId = firstNonBlank(item.sourceId(), item.staffId(), item.accountId());
                    if (sourceStaffId == null) {
                        failed++;
                        failures.add("sourceStaffId为空");
                        continue;
                    }
                    String payloadJson = payloadJson(item.attributes());
                    String payloadHash = sha256(payloadJson);
                    raw.add(new RawLanding("STAFF", sourceStaffId, item.updatedAt(), item.attributes()));
                    rows.add(new DhbStaffRow(bucket.key.connectorId(), sourceTenantKey, sourceStaffId,
                            item.staffType(), item.accountName(), item.staffName(), item.title(),
                            item.branchName(), item.accountMobile(), item.remark(), item.roleName(),
                            item.inviteCode(), item.mobile(), item.email(), item.qq(), item.status(),
                            item.createdAt(), item.updatedAt(), payloadHash, payloadJson));
                }
                store.persistRawLandings(caller.tenantId(), bucket.key.connectorId(), raw);
                if (!rows.isEmpty()) {
                    StaffSyncResult result = iamClient.sync(caller, rows);
                    created += result.created();
                    updated += result.updated();
                    unchanged += result.unchanged();
                    failed += result.failed();
                    failures.addAll(result.failureMessages());
                }
                if (!page.hasNext()) break;
                request = page.nextRequest();
            }
            String status = failed > 0 ? "SUCCEEDED_WITH_WARNINGS" : "SUCCEEDED";
            String message = failed > 0
                    ? "失败" + failed + "条；未变化" + unchanged + "条；" + oneLine(String.join("；", failures))
                    : "未变化" + unchanged + "条";
            steps.add(new DhbSyncOrchestrationStepView("IAM", "STAFF", status, null,
                    fetched, created + updated, failed, Map.of(), message));
            return false;
        } catch (RuntimeException error) {
            steps.add(failed("IAM", "STAFF", error));
            return true;
        }
    }

    private boolean runErpSteps(TargetBucket bucket, CallerIdentity caller, int maxPages,
                                List<DhbSyncOrchestrationStepView> steps) {
        if (bucket.productTarget == null) {
            steps.add(skipped("ERP", "PRODUCT_MASTER_DATA", "未配置启用的商品主数据同步任务"));
            return false;
        }
        for (String objectType : PRODUCT_OBJECTS) {
            try {
                ErpDataSyncResult result = erpClient.sync(caller, bucket.key.connectorId(),
                        bucket.productTarget.taskId(), objectType, maxPages);
                steps.add(erpStep(result));
            } catch (RuntimeException error) {
                steps.add(failed("ERP", objectType, error));
                return true;
            }
        }
        return false;
    }

    private boolean runSupplySteps(TargetBucket bucket, CallerIdentity caller, int maxPages,
                                   List<DhbSyncOrchestrationStepView> steps) {
        if (bucket.supplyTarget == null) {
            steps.add(skipped("ERP", "SUPPLY_CHAIN_DATA", "未配置启用的供应链同步任务"));
            return false;
        }
        for (String objectType : SUPPLY_OBJECTS) {
            try {
                ErpDataSyncResult result = erpClient.sync(caller, bucket.key.connectorId(),
                        bucket.supplyTarget.taskId(), objectType, maxPages);
                steps.add(erpStep(result));
            } catch (RuntimeException error) {
                steps.add(failed("ERP", objectType, error));
                return true;
            }
        }
        return false;
    }

    private boolean runCrmStep(TargetBucket bucket, CallerIdentity caller, int maxPages,
                               List<DhbSyncOrchestrationStepView> steps) {
        if (bucket.crmTarget == null) {
            steps.add(skipped("CRM", "CRM_MASTER_DATA", "未配置启用的CRM同步任务"));
            return false;
        }
        try {
            SyncResult result = crmClient.sync(serviceCaller(bucket.key.tenantId()), bucket.key.connectorId(),
                    bucket.crmTarget.taskId(), maxPages);
            for (SyncObjectResult object : result.objects()) steps.add(crmStep(object));
            return false;
        } catch (RuntimeException error) {
            steps.add(failed("CRM", "CRM_MASTER_DATA", error));
            return true;
        }
    }

    private void runOrderStep(TargetBucket bucket, CallerIdentity caller, int maxPages,
                              List<DhbSyncOrchestrationStepView> steps) {
        if (bucket.orderTarget == null) {
            steps.add(skipped("ORDER", "ORDER_DOMAIN", "未配置启用的Order同步任务"));
            return;
        }
        try {
            SyncRunView result = orderSyncService.runOrderPull(caller, bucket.orderTarget.taskId(),
                    null, maxPages);
            steps.add(orderStep(result));
        } catch (RuntimeException error) {
            steps.add(failed("ORDER", "ORDER_DOMAIN", error));
        }
    }

    private Map<TenantConnector, TargetBucket> targets(UUID tenantFilter) {
        Map<TenantConnector, TargetBucket> result = new LinkedHashMap<>();
        addTargets(result, store.activeProductMasterSyncTargets(), tenantFilter, (bucket, target) ->
                bucket.productTarget = target);
        addTargets(result, store.activeSupplyChainSyncTargets(), tenantFilter, (bucket, target) ->
                bucket.supplyTarget = target);
        addTargets(result, store.activeCrmMasterSyncTargets(), tenantFilter, (bucket, target) ->
                bucket.crmTarget = target);
        addTargets(result, store.activeOrderSyncTargets(), tenantFilter, (bucket, target) ->
                bucket.orderTarget = target);
        addTargets(result, store.activeBusinessDictionarySyncTargets(), tenantFilter, (bucket, target) ->
                bucket.dictionaryTarget = target);
        return result;
    }

    private void addTargets(Map<TenantConnector, TargetBucket> buckets, List<SyncTargetView> values,
                            UUID tenantFilter, TargetSetter setter) {
        if (values == null) return;
        for (SyncTargetView target : values) {
            if (target == null || target.tenantId() == null || target.connectorId() == null
                    || target.taskId() == null) continue;
            if (tenantFilter != null && !tenantFilter.equals(target.tenantId())) continue;
            TenantConnector key = new TenantConnector(target.tenantId(), target.connectorId());
            TargetBucket bucket = buckets.computeIfAbsent(key, TargetBucket::new);
            setter.set(bucket, target);
        }
    }

    private static DhbSyncOrchestrationStepView erpStep(ErpDataSyncResult result) {
        return new DhbSyncOrchestrationStepView("ERP", result.objectType(), result.status(),
                result.runId(), result.fetched(), result.created() + result.changed(),
                result.unmapped(), result.dictionaryRevisions(), erpMessage(result));
    }

    private static String erpMessage(ErpDataSyncResult result) {
        if (result.sourceDetails().isEmpty()) return null;
        String details = result.sourceDetails().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("；"));
        return "来源明细：" + details + "；未变化" + result.duplicates()
                + "条；拒绝" + result.rejected() + "条";
    }

    private static String dictionaryMessage(Audit audit) {
        if (audit == null || audit.unmapped() == 0) {
            return "基础来源枚举已补齐；领域同步批次仍会按实际来源值继续审计";
        }
        String issues = audit.issues().stream()
                .limit(8)
                .map(DhbSyncOrchestrationService::dictionaryIssue)
                .collect(java.util.stream.Collectors.joining("；"));
        return "基础来源枚举存在未解析值" + audit.unmapped() + "条：" + issues;
    }

    private static String dictionaryIssue(MappingIssue issue) {
        return issue.dictionaryCode() + "." + issue.fieldCode() + "=" + issue.sourceValue()
                + "x" + issue.count();
    }

    private static DhbSyncOrchestrationStepView crmStep(SyncObjectResult result) {
        return new DhbSyncOrchestrationStepView("CRM", result.objectType(), result.status(),
                result.runId(), result.fetched(), result.created() + result.changed() + result.repaired(),
                result.unmapped(), result.dictionaryRevisions(), null);
    }

    private static DhbSyncOrchestrationStepView orderStep(SyncRunView result) {
        return new DhbSyncOrchestrationStepView("ORDER", "ORDER_DOMAIN", result.status(),
                result.runId(), result.fetchedCount(), result.acceptedCount(),
                result.rejectedCount(), Map.of(), result.errorMessage());
    }

    private static DhbSyncOrchestrationStepView skipped(String domain, String objectType, String message) {
        return new DhbSyncOrchestrationStepView(domain, objectType, "SKIPPED",
                null, 0, 0, 0, Map.of(), message);
    }

    private static DhbSyncOrchestrationStepView failed(String domain, String objectType, RuntimeException error) {
        return new DhbSyncOrchestrationStepView(domain, objectType, "FAILED",
                null, 0, 0, 0, Map.of(), oneLine(error.getMessage()));
    }

    private DhbClient.Connector connector(TargetBucket bucket) {
        var view = store.connector(bucket.key.tenantId(), bucket.key.connectorId());
        return new DhbClient.Connector(bucket.key.tenantId(), view.id(), view.baseUrl(), view.authSecretRef());
    }

    private String payloadJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("订货宝员工原始字段序列化失败", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static String sourceTenantKey(TargetBucket bucket) {
        return bucket.key.connectorId().toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return null;
    }

    private static String aggregateStepStatus(List<DhbSyncOrchestrationStepView> steps) {
        if (steps.stream().anyMatch(step -> "FAILED".equals(step.status()))) return "FAILED";
        if (steps.stream().anyMatch(step -> "PARTIAL".equals(step.status()))) return "PARTIAL";
        if (steps.stream().anyMatch(step -> "SUCCEEDED_WITH_WARNINGS".equals(step.status()))) {
            return "SUCCEEDED_WITH_WARNINGS";
        }
        if (steps.stream().allMatch(step -> "SKIPPED".equals(step.status()))) return "SKIPPED";
        if (steps.stream().anyMatch(step -> "SKIPPED".equals(step.status()))) return "SUCCEEDED_WITH_WARNINGS";
        return "SUCCEEDED";
    }

    private static String aggregateTenantStatus(List<DhbSyncOrchestrationTenantView> tenants) {
        if (tenants.isEmpty()) return "SKIPPED";
        if (tenants.stream().anyMatch(tenant -> "FAILED".equals(tenant.status()))) return "FAILED";
        if (tenants.stream().anyMatch(tenant -> "PARTIAL".equals(tenant.status()))) return "PARTIAL";
        if (tenants.stream().anyMatch(tenant -> "SUCCEEDED_WITH_WARNINGS".equals(tenant.status()))) {
            return "SUCCEEDED_WITH_WARNINGS";
        }
        if (tenants.stream().allMatch(tenant -> "SKIPPED".equals(tenant.status()))) return "SKIPPED";
        if (tenants.stream().anyMatch(tenant -> "SKIPPED".equals(tenant.status()))) return "SUCCEEDED_WITH_WARNINGS";
        return "SUCCEEDED";
    }

    private void logStepFailures(UUID batchId, List<DhbSyncOrchestrationTenantView> tenants) {
        for (DhbSyncOrchestrationTenantView tenant : tenants) {
            for (DhbSyncOrchestrationStepView step : tenant.steps()) {
                if (!"FAILED".equals(step.status()) && !"PARTIAL".equals(step.status())
                        && !"SUCCEEDED_WITH_WARNINGS".equals(step.status())) {
                    continue;
                }
                log.warn("订货宝统一同步步骤异常 batchId={} tenantId={} connectorId={} domain={} objectType={} "
                                + "status={} runId={} fetched={} changed={} unmapped={} message={}",
                        batchId, tenant.tenantId(), tenant.connectorId(), step.domain(), step.objectType(),
                        step.status(), step.runId(), step.fetched(), step.changed(), step.unmapped(),
                        oneLine(step.message()));
            }
        }
    }

    private static int maxPages(Integer value) {
        int result = value == null ? 100 : value;
        if (result < 1 || result > 100) {
            throw new IllegalArgumentException("maxPages必须在1到100之间");
        }
        return result;
    }

    private static boolean enabled(Boolean value) {
        return value == null || value;
    }

    private static boolean enabled(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static void requireManualCaller(CallerIdentity caller) {
        if (caller == null || caller.tenantId() == null || caller.userId() == null) {
            throw new AuthorizationDeniedException("tenant-user-caller");
        }
        if ((!caller.permissions().contains("integration:dhb:read")
                || !caller.permissions().contains("integration:dhb:write"))
                && !caller.permissions().contains("*:*:*")) {
            throw new AuthorizationDeniedException("integration:dhb:write");
        }
    }

    private static CallerIdentity serviceCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("DHB_SYNC_ORCHESTRATOR"), SERVICE_PERMISSIONS);
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private record TenantConnector(UUID tenantId, UUID connectorId) { }

    private static final class TargetBucket {
        private final TenantConnector key;
        private SyncTargetView dictionaryTarget;
        private SyncTargetView productTarget;
        private SyncTargetView supplyTarget;
        private SyncTargetView crmTarget;
        private SyncTargetView orderTarget;

        private TargetBucket(TenantConnector key) { this.key = key; }
    }

    @FunctionalInterface
    private interface TargetSetter {
        void set(TargetBucket bucket, SyncTargetView target);
    }
}
