package com.rigour.integration.application.service.feishu;

import com.rigour.erp.api.v1.model.ExternalProductResolveRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolvedView;
import com.rigour.erp.api.v1.model.ExternalProductRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncResult;
import com.rigour.erp.api.v1.model.ExternalProductSyncRowResult;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportBatchSummary;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportIssueView;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportPreflightResult;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunCommand;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunIssueSummaryView;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunResult;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunRowView;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportTablePreview;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportTemplateDependencyView;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportTemplateView;
import com.rigour.integration.application.port.out.CrmCustomerProjectionClient;
import com.rigour.integration.application.port.out.ErpProductProjectionClient;
import com.rigour.integration.application.port.out.FeishuImportStore;
import com.rigour.integration.application.port.out.FeishuImportStore.ExistingDeduplicationRow;
import com.rigour.integration.application.port.out.FeishuImportStore.ImportTemplate;
import com.rigour.integration.application.port.out.FeishuImportStore.PreflightBatch;
import com.rigour.integration.application.port.out.FeishuImportStore.PreflightIssue;
import com.rigour.integration.application.port.out.FeishuImportStore.PreflightRawRow;
import com.rigour.integration.application.port.out.FeishuImportStore.PreflightTable;
import com.rigour.integration.application.port.out.FeishuImportStore.ProjectionIssueSummary;
import com.rigour.integration.application.port.out.FeishuImportStore.RowProjectionUpdate;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import com.rigour.integration.application.service.feishu.FeishuAttachmentImportService.AttachmentFailure;
import com.rigour.integration.application.service.feishu.FeishuAttachmentImportService.AttachmentResolution;
import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncRowResult;
import com.rigour.integration.application.port.out.HrEmployeeProjectionClient;
import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.integration.infrastructure.config.FeishuImportProperties;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncResult;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncRowResult;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncResult;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncRowResult;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineCommand;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.sync.ExternalSourceCodes;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** 飞书导出文件批次预检服务；正式导入前先建立可审计、可重跑的导入批次。 */
public final class FeishuImportBundleService {
    private static final Logger log = LoggerFactory.getLogger(FeishuImportBundleService.class);
    public static final String SOURCE_SYSTEM = "FEISHU";
    private static final UUID SERVICE_PRINCIPAL_ID = UUID.nameUUIDFromBytes(
            "rigour-integration-feishu-import-service".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> HR_EMPLOYEE_PROJECTION_PERMISSIONS = Set.of("hr:employee:sync", "hr:employee:read");
    private static final Set<String> CRM_CUSTOMER_PROJECTION_PERMISSIONS = Set.of("crm:customer:sync");
    private static final Set<String> ERP_PRODUCT_PROJECTION_PERMISSIONS = Set.of("erp:product:sync");
    private static final Set<String> ORDER_SALES_PROJECTION_PERMISSIONS = Set.of("order:read", "order:write");
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final int DEFAULT_RUN_LIMIT = 100_000;
    private static final int MAX_RUN_LIMIT = 100_000;
    private static final int DOMAIN_PROJECTION_BATCH_SIZE = 50;
    private static final int PROJECTION_PROGRESS_FLUSH_SIZE = 50;
    private static final int RUN_ISSUE_SUMMARY_LIMIT = 30;
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final ZoneId SOURCE_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern LEADING_DATE_PATTERN =
            Pattern.compile("^(\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2})");
    private static final Pattern EMBEDDED_YYYYMMDD_PATTERN = Pattern.compile("(20\\d{6})");
    private static final Pattern CODE_PREFIX_PATTERN =
            Pattern.compile("^([A-Za-z]{1,12}\\d{2,})\\s*[-_：: ]+.*$");
    private static final List<String> SOURCE_DOCUMENT_FIELDS = List.of(
            "订单编号", "订单明细号", "物流编码", "发票编号", "编号", "回款编号",
            "申请编号", "活动编号", "采购编号", "发货单标题", "商家编号", "门店编码",
            "产品编码", "客户编号", "商品编号", "来源单号", "ID", "订单编号门店",
            "回款日期门店", "商家编号名称", "门店编码名称", "产品编码名称", "概要",
            "城市人仓", "人员战报", "文本");
    private static final List<String> STAFF_SOURCE_DOCUMENT_FIELDS = List.of(
            "销售姓名", "人员编号", "员工编号", "员工编码", "工号", "ID", "手机号", "姓名");
    private static final List<String> SOURCE_CREATED_FIELDS = List.of(
            "创建时间", "新建时间", "下单时间", "订单时间", "销售日期", "回款日期",
            "申请日期", "发货日期", "发现日期", "拜访日期", "日期", "最后更新时间",
            "合作日期", "有效截至日期", "修改时间");
    private static final List<String> STAFF_SOURCE_CREATED_FIELDS = List.of(
            "创建时间", "新建时间", "入职日期", "销售姓名");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.M.d"));

    private final FeishuImportStore store;
    private final FeishuImportProperties properties;
    private final Clock clock;
    private final FeishuImportXlsxInspector inspector;
    private final ObjectMapper objectMapper;
    private final OrderSalesOrderProjectionClient orderSalesOrderProjectionClient;
    private final HrEmployeeProjectionClient hrEmployeeProjectionClient;
    private final CrmCustomerProjectionClient crmCustomerProjectionClient;
    private final ErpProductProjectionClient erpProductProjectionClient;
    private final BusinessDictionaryBatchClient businessDictionaryBatchClient;
    private final FeishuSalesOrderImportMapper salesOrderImportMapper;
    private final FeishuStaffImportMapper staffImportMapper;
    private final FeishuCrmAreaImportMapper crmAreaImportMapper;
    private final FeishuCrmCustomerImportMapper crmCustomerImportMapper;
    private final FeishuErpProductImportMapper erpProductImportMapper;
    private final FeishuSalesPaymentImportMapper salesPaymentImportMapper;
    private final FeishuDictionaryObservationMapper dictionaryObservationMapper;
    private final FeishuAttachmentImportService attachmentImportService;

    public FeishuImportBundleService(FeishuImportStore store, FeishuImportProperties properties,
                                     Clock clock) {
        this(store, null, properties, clock);
    }

    public FeishuImportBundleService(FeishuImportStore store,
                                     OrderSalesOrderProjectionClient orderSalesOrderProjectionClient,
                                     FeishuImportProperties properties,
                                     Clock clock) {
        this(store, orderSalesOrderProjectionClient, null, properties, clock);
    }

    public FeishuImportBundleService(FeishuImportStore store,
                                     OrderSalesOrderProjectionClient orderSalesOrderProjectionClient,
                                     HrEmployeeProjectionClient hrEmployeeProjectionClient,
                                     FeishuImportProperties properties,
                                     Clock clock) {
        this(store, orderSalesOrderProjectionClient, hrEmployeeProjectionClient,
                null, null, null, properties, clock);
    }

    public FeishuImportBundleService(FeishuImportStore store,
                                     OrderSalesOrderProjectionClient orderSalesOrderProjectionClient,
                                     HrEmployeeProjectionClient hrEmployeeProjectionClient,
                                     CrmCustomerProjectionClient crmCustomerProjectionClient,
                                     ErpProductProjectionClient erpProductProjectionClient,
                                     BusinessDictionaryBatchClient businessDictionaryBatchClient,
                                     FeishuImportProperties properties,
                                     Clock clock) {
        this(store, orderSalesOrderProjectionClient, hrEmployeeProjectionClient,
                crmCustomerProjectionClient, erpProductProjectionClient, businessDictionaryBatchClient,
                null, properties, clock);
    }

    public FeishuImportBundleService(FeishuImportStore store,
                                     OrderSalesOrderProjectionClient orderSalesOrderProjectionClient,
                                     HrEmployeeProjectionClient hrEmployeeProjectionClient,
                                     CrmCustomerProjectionClient crmCustomerProjectionClient,
                                     ErpProductProjectionClient erpProductProjectionClient,
                                     BusinessDictionaryBatchClient businessDictionaryBatchClient,
                                     FeishuAttachmentImportService attachmentImportService,
                                     FeishuImportProperties properties,
                                     Clock clock) {
        this(store, orderSalesOrderProjectionClient, hrEmployeeProjectionClient,
                crmCustomerProjectionClient, erpProductProjectionClient, businessDictionaryBatchClient,
                attachmentImportService, properties, clock,
                new FeishuImportXlsxInspector(), new ObjectMapper(),
                new FeishuSalesOrderImportMapper(), new FeishuStaffImportMapper(),
                new FeishuCrmAreaImportMapper(),
                new FeishuCrmCustomerImportMapper(), new FeishuErpProductImportMapper(),
                new FeishuSalesPaymentImportMapper(),
                new FeishuDictionaryObservationMapper());
    }

    FeishuImportBundleService(FeishuImportStore store, FeishuImportProperties properties,
                              Clock clock, FeishuImportXlsxInspector inspector) {
        this(store, null, null, null, null, null, null, properties, clock, inspector, new ObjectMapper(),
                new FeishuSalesOrderImportMapper(), new FeishuStaffImportMapper(),
                new FeishuCrmAreaImportMapper(),
                new FeishuCrmCustomerImportMapper(), new FeishuErpProductImportMapper(),
                new FeishuSalesPaymentImportMapper(),
                new FeishuDictionaryObservationMapper());
    }

    FeishuImportBundleService(FeishuImportStore store,
                              OrderSalesOrderProjectionClient orderSalesOrderProjectionClient,
                              HrEmployeeProjectionClient hrEmployeeProjectionClient,
                              CrmCustomerProjectionClient crmCustomerProjectionClient,
                              ErpProductProjectionClient erpProductProjectionClient,
                              BusinessDictionaryBatchClient businessDictionaryBatchClient,
                              FeishuAttachmentImportService attachmentImportService,
                              FeishuImportProperties properties,
                              Clock clock,
                              FeishuImportXlsxInspector inspector,
                              ObjectMapper objectMapper,
                              FeishuSalesOrderImportMapper salesOrderImportMapper,
                              FeishuStaffImportMapper staffImportMapper,
                              FeishuCrmAreaImportMapper crmAreaImportMapper,
                              FeishuCrmCustomerImportMapper crmCustomerImportMapper,
                              FeishuErpProductImportMapper erpProductImportMapper,
                              FeishuSalesPaymentImportMapper salesPaymentImportMapper,
                              FeishuDictionaryObservationMapper dictionaryObservationMapper) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.inspector = inspector;
        this.objectMapper = objectMapper;
        this.orderSalesOrderProjectionClient = orderSalesOrderProjectionClient;
        this.hrEmployeeProjectionClient = hrEmployeeProjectionClient;
        this.crmCustomerProjectionClient = crmCustomerProjectionClient;
        this.erpProductProjectionClient = erpProductProjectionClient;
        this.businessDictionaryBatchClient = businessDictionaryBatchClient;
        this.salesOrderImportMapper = salesOrderImportMapper;
        this.staffImportMapper = staffImportMapper;
        this.crmAreaImportMapper = crmAreaImportMapper;
        this.crmCustomerImportMapper = crmCustomerImportMapper;
        this.erpProductImportMapper = erpProductImportMapper;
        this.salesPaymentImportMapper = salesPaymentImportMapper;
        this.dictionaryObservationMapper = dictionaryObservationMapper;
        this.attachmentImportService = attachmentImportService;
    }

    public FeishuImportPreflightResult preflight(CallerIdentity caller, MultipartFile file,
                                                 String sourceUrl) {
        return preflight(caller, file == null ? List.of() : List.of(file), sourceUrl);
    }

    public FeishuImportPreflightResult preflight(CallerIdentity caller, List<MultipartFile> files,
                                                 String sourceUrl) {
        properties.validate();
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "飞书导入中心未启用", List.of());
        }
        if (caller == null || caller.tenantId() == null || caller.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "飞书导入需要租户用户身份", List.of());
        }
        List<MultipartFile> importFiles = files == null
                ? List.of()
                : files.stream().filter(item -> item != null && !item.isEmpty()).toList();
        if (importFiles.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请上传飞书导出的xlsx文件", List.of());
        }
        if (importFiles.size() > properties.getMaxSheets()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "一次导入文件数量超过限制", List.of());
        }

        List<ImportTemplate> templates = importTemplates(caller.tenantId());
        List<FeishuImportXlsxInspector.SheetInspection> sheets = new ArrayList<>();
        List<FeishuImportXlsxInspector.Issue> issues = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        MessageDigest batchDigest = sha256Digest();
        long totalBytes = 0L;
        try {
            for (MultipartFile file : importFiles) {
                String fileName = safeFileName(file.getOriginalFilename());
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "当前预检只支持飞书导出的xlsx文件", List.of());
                }
                Path temporaryFile = null;
                try {
                    temporaryFile = Files.createTempFile("rigour-feishu-import-", ".xlsx");
                    CopyResult copy = copyToTemp(file, temporaryFile);
                    totalBytes += copy.bytes();
                    batchDigest.update(fileName.getBytes(StandardCharsets.UTF_8));
                    batchDigest.update((byte) 0);
                    batchDigest.update(copy.sha256().getBytes(StandardCharsets.UTF_8));
                    batchDigest.update((byte) 0);
                    FeishuImportXlsxInspector.Inspection inspection =
                            inspector.inspect(temporaryFile, properties, templates);
                    sheets.addAll(inspection.sheets());
                    issues.addAll(inspection.issues());
                    fileNames.add(fileName);
                } finally {
                    if (temporaryFile != null) {
                        try {
                            Files.deleteIfExists(temporaryFile);
                        } catch (IOException ignored) {
                            // 临时文件删除失败不影响本次预检结果。
                        }
                    }
                }
            }
            FeishuImportXlsxInspector.Inspection inspection =
                    new FeishuImportXlsxInspector.Inspection(sheets, issues);
            CopyResult copy = new CopyResult(totalBytes, HexFormat.of().formatHex(batchDigest.digest()));
            return persistResult(caller, batchFileName(fileNames), copy, clean(sourceUrl), inspection, null);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage(), List.of());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "飞书导出文件读取失败", List.of());
        }
    }

    public FeishuImportRunResult run(CallerIdentity caller, UUID batchId,
                                     FeishuImportRunCommand command) {
        properties.validate();
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "飞书导入中心未启用", List.of());
        }
        if (caller == null || caller.tenantId() == null || caller.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "飞书导入需要租户用户身份", List.of());
        }
        if (batchId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "导入批次ID不能为空", List.of());
        }
        FeishuImportStore.StoredBatch batch = store.batch(caller.tenantId(), batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "飞书导入批次不存在", List.of()));
        if ("REJECTED".equals(batch.status()) || "CANCELLED".equals(batch.status())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前导入批次状态不允许执行", List.of());
        }

        boolean dryRun = command != null && Boolean.TRUE.equals(command.dryRun());
        boolean replayProjected = !dryRun || (command != null && Boolean.TRUE.equals(command.replayProjected()));
        int limit = runLimit(command);
        Instant now = clock.instant();
        if (!dryRun) {
            store.updateBatchStatus(caller.tenantId(), batchId, "RUNNING", caller.userId(), now);
            log.info("飞书导入正式执行开始 tenantId={} batchId={} maxRows={} replayProjected={}",
                    caller.tenantId(), batchId, limit, replayProjected);
        }
        try {
            List<StoredRawRow> rows = store.rawRowsForRun(caller.tenantId(), batchId, limit, replayProjected);
            log.info("飞书导入原始行加载完成 tenantId={} batchId={} rows={} dryRun={}",
                    caller.tenantId(), batchId, rows.size(), dryRun);
            AttachmentResolution attachmentResolution = dryRun || attachmentImportService == null
                    ? AttachmentResolution.unchanged(rows)
                    : attachmentImportService.resolve(caller, batch, rows, caller.userId(), now);
            rows = attachmentResolution.rows();
            if (!dryRun) {
                log.info("飞书导入附件处理完成 tenantId={} batchId={} uploaded={} failedRows={}",
                        caller.tenantId(), batchId, attachmentResolution.uploadedAttachmentCount(),
                        attachmentResolution.failedAttachmentRows());
                syncDictionaries(caller, rows);
            }
            Map<String, String> crmAreaCodes = dryRun ? Map.of() : syncCrmAreaMasters(caller, rows);
            List<FeishuImportRunRowView> views = new ArrayList<>();
            int projected = 0;
            int skipped = 0;
            int waiting = 0;
            int failed = 0;
            List<RowProjectionUpdate> projectionUpdates = new ArrayList<>();
            ProjectionProgressRecorder progressRecorder =
                    new ProjectionProgressRecorder(store, caller, now, dryRun);
            Map<UUID, ProjectionDecision> decisions =
                    projectionDecisions(caller, rows, dryRun, progressRecorder, crmAreaCodes);
            for (AttachmentFailure failure : attachmentResolution.failures().values()) {
                ProjectionDecision attachmentDecision = new ProjectionDecision("WAITING_MAPPING",
                        failure.targetDomain(), failure.targetObjectType(), null,
                        failure.errorCode(), failure.message());
                decisions.merge(failure.rawRowId(), attachmentDecision, FeishuImportBundleService::withAttachmentIssue);
            }
            for (StoredRawRow row : rows) {
                ProjectionDecision decision = decisions.getOrDefault(row.id(),
                        new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                                null, "FEISHU_PROJECTION_DECISION_MISSING", "未生成投影结果"));
                switch (decision.status()) {
                    case "PROJECTED" -> projected++;
                    case "SKIPPED" -> skipped++;
                    case "FAILED" -> failed++;
                    default -> waiting++;
                }
                views.add(new FeishuImportRunRowView(row.id(), row.sheetName(), row.rowNumber(),
                        row.tableCode(), row.sourceDocumentNo(), decision.status(),
                        decision.targetDomain(), decision.targetObjectType(), decision.targetId(),
                        decision.message()));
                if (!dryRun && !progressRecorder.wasFlushed(row.id(), decision)) {
                    projectionUpdates.add(rowProjectionUpdate(caller, row, decision, now));
                }
            }

            String status = runStatus(projected, skipped, waiting, failed, rows.size(), dryRun);
            if (!dryRun && attachmentResolution.failedAttachmentRows() > 0 && "SUCCEEDED".equals(status)) {
                status = "PARTIAL";
            }
            if (!dryRun) {
                store.updateRawRowProjections(projectionUpdates);
                store.updateBatchStatus(caller.tenantId(), batchId, status, caller.userId(), now);
                log.info("飞书导入正式执行完成 tenantId={} batchId={} status={} projected={} skipped={} waiting={} failed={}",
                        caller.tenantId(), batchId, status, projected, skipped, waiting, failed);
            }
            List<FeishuImportRunIssueSummaryView> issueSummaries = runIssueSummariesFromRows(rows, decisions);
            return new FeishuImportRunResult(batchId, status, dryRun, rows.size(), projected,
                    skipped, waiting, failed, now, attachmentResolution.uploadedAttachmentCount(),
                    attachmentResolution.failedAttachmentRows(), issueSummaries, views);
        } catch (RuntimeException exception) {
            if (!dryRun) {
                store.updateBatchStatus(caller.tenantId(), batchId, "FAILED", caller.userId(), clock.instant());
                log.error("飞书导入正式执行异常 tenantId={} batchId={} errorType={} reason={}",
                        caller.tenantId(), batchId, exception.getClass().getSimpleName(),
                        clean(exception.getMessage()), exception);
            }
            throw exception;
        }
    }

    public FeishuImportRunResult runStatus(CallerIdentity caller, UUID batchId, Integer limit) {
        properties.validate();
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "飞书导入中心未启用", List.of());
        }
        if (caller == null || caller.tenantId() == null || caller.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "飞书导入需要租户用户身份", List.of());
        }
        if (batchId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "导入批次ID不能为空", List.of());
        }
        FeishuImportStore.StoredBatch batch = store.batch(caller.tenantId(), batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "飞书导入批次不存在", List.of()));
        int pageSize = Math.max(1, Math.min(limit == null ? 500 : limit, MAX_RUN_LIMIT));
        List<StoredRawRow> rows = store.rawRowsForBatch(caller.tenantId(), batchId, pageSize);
        Map<String, Long> counts = store.rawRowProjectionStatusCounts(caller.tenantId(), batchId);
        int projected = count(counts, "PROJECTED");
        int skipped = count(counts, "SKIPPED");
        int failed = count(counts, "FAILED");
        int waiting = count(counts, "PENDING") + count(counts, "WAITING_MAPPING");
        List<FeishuImportRunIssueSummaryView> issueSummaries =
                runIssueSummariesFromStore(store.rawRowProjectionIssueSummaries(caller.tenantId(), batchId));
        List<FeishuImportRunRowView> views = rows.stream()
                .map(row -> new FeishuImportRunRowView(row.id(), row.sheetName(), row.rowNumber(),
                        row.tableCode(), row.sourceDocumentNo(),
                        firstNonBlank(row.projectionStatus(), "PENDING"),
                        firstNonBlank(row.targetDomain(), row.domainCode()),
                        firstNonBlank(row.targetObjectType(), row.objectType()),
                        row.targetId(),
                        firstNonBlank(row.errorMessage(), projectionStatusMessage(row.projectionStatus()))))
                .toList();
        int total = Math.toIntExact(Math.min(batch.totalRows(), Integer.MAX_VALUE));
        String status = firstNonBlank(batch.status(), runStatus(projected, skipped, waiting, failed, total, false));
        FeishuImportStore.RawRowAttachmentStatus attachmentStatus =
                store.rawRowAttachmentStatus(caller.tenantId(), batchId);
        return new FeishuImportRunResult(batchId, status, false, total,
                projected, skipped, waiting, failed, batch.updatedAt(),
                attachmentStatus.uploadedAttachmentCount(), attachmentStatus.failedAttachmentRows(),
                issueSummaries, views);
    }

    public List<FeishuImportBatchSummary> batches(CallerIdentity caller, Integer limit) {
        properties.validate();
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "飞书导入中心未启用", List.of());
        }
        if (caller == null || caller.tenantId() == null || caller.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "飞书导入需要租户用户身份", List.of());
        }
        int pageSize = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        return store.recentBatches(caller.tenantId(), pageSize).stream()
                .map(batch -> new FeishuImportBatchSummary(batch.id(), batch.status(),
                        batch.sourceSystem(), batch.originalFileName(),
                        batch.fileSizeBytes(), batch.fileSha256(), batch.sourceUrl(),
                        batch.totalSheets(), batch.totalRows(), batch.duplicateRows(),
                        batch.attachmentReferenceCount(), batch.createdAt(), batch.updatedAt()))
                .toList();
    }

    public List<FeishuImportTemplateView> templates(CallerIdentity caller) {
        properties.validate();
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "飞书导入中心未启用", List.of());
        }
        if (caller == null || caller.tenantId() == null || caller.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "飞书导入需要租户用户身份", List.of());
        }
        return importTemplates(caller.tenantId()).stream()
                .map(FeishuImportBundleService::templateView)
                .toList();
    }

    private FeishuImportPreflightResult persistResult(CallerIdentity caller, String fileName,
                                                      CopyResult copy,
                                                      String sourceUrl,
                                                      FeishuImportXlsxInspector.Inspection inspection,
                                                      Path uploadedFile) {
        UUID batchId = UUID.randomUUID();
        Instant now = clock.instant();
        List<FeishuImportTablePreview> tableViews = new ArrayList<>();
        List<PreflightTable> tables = new ArrayList<>();
        List<FeishuImportTableCatalog.Match> matches = new ArrayList<>();
        long totalRows = 0L;
        long totalAttachments = 0L;
        for (FeishuImportXlsxInspector.SheetInspection sheet : inspection.sheets()) {
            FeishuImportTableCatalog.Match match = sheet.catalogMatch();
            matches.add(match);
            totalRows += sheet.rowCount();
            totalAttachments += sheet.attachmentReferenceCount();
            tables.add(new PreflightTable(UUID.randomUUID(), sheet.sheetName(), match.tableCode(),
                    match.domainCode(), match.objectType(), match.mappingStatus(),
                    sheet.headerRowNumber(), sheet.rowCount(), 0L, sheet.columnCount(),
                    sheet.attachmentReferenceCount(), sheet.headers(), match.attachmentFields()));
        }
        List<PreflightRawRow> rawRows = new ArrayList<>();
        HashSet<String> deduplicationKeys = new HashSet<>();
        Map<String, Long> missingSourceDocumentNo = new TreeMap<>();
        Map<String, Long> missingSourceCreatedAt = new TreeMap<>();
        for (int sheetIndex = 0; sheetIndex < inspection.sheets().size(); sheetIndex++) {
            FeishuImportXlsxInspector.SheetInspection sheet = inspection.sheets().get(sheetIndex);
            UUID tableId = tables.get(sheetIndex).id();
            FeishuImportTableCatalog.Match match = matches.get(sheetIndex);
            for (FeishuImportXlsxInspector.RowInspection row : sheet.rows()) {
                String sourceDocumentNo = sourceDocumentNo(row.values(), match);
                Instant sourceCreatedAt = sourceCreatedAt(row.values(), match);
                if (sourceDocumentNo == null) {
                    missingSourceDocumentNo.merge(sheet.sheetName(), 1L, Long::sum);
                }
                if (sourceCreatedAt == null) {
                    missingSourceCreatedAt.merge(sheet.sheetName(), 1L, Long::sum);
                }
                String rowHash = rowHash(row.values());
                String deduplicationKey = deduplicationKey(match, row.values(), sourceDocumentNo, rowHash);
                if (deduplicationKey != null) deduplicationKeys.add(deduplicationKey);
                rawRows.add(new PreflightRawRow(UUID.randomUUID(), tableId, sheet.sheetName(),
                        match.tableCode(), match.domainCode(), match.objectType(),
                        row.rowNumber(), sourceDocumentNo, sourceCreatedAt, rowHash, row.values(),
                        row.attachmentRefs(), deduplicationKey, null, null, "IMPORTED", "PENDING"));
            }
        }
        Map<String, ExistingDeduplicationRow> existingRows =
                store.existingDeduplicationRows(caller.tenantId(), deduplicationKeys);
        DeduplicationResult deduplication = applyDeduplication(rawRows, existingRows);
        rawRows = deduplication.rows();
        Map<UUID, Long> duplicateRowsByTable = new HashMap<>();
        for (PreflightRawRow rawRow : rawRows) {
            if (rawRow.duplicateScope() != null) duplicateRowsByTable.merge(rawRow.tableId(), 1L, Long::sum);
        }
        List<PreflightTable> tablesWithDuplicates = new ArrayList<>(tables.size());
        for (PreflightTable table : tables) {
            long duplicateRows = duplicateRowsByTable.getOrDefault(table.id(), 0L);
            tablesWithDuplicates.add(new PreflightTable(table.id(), table.sheetName(), table.tableCode(),
                    table.domainCode(), table.objectType(), table.mappingStatus(), table.headerRowNumber(),
                    table.rowCount(), duplicateRows, table.columnCount(), table.attachmentReferenceCount(),
                    table.headers(), table.attachmentFields()));
        }
        tables = tablesWithDuplicates;
        for (int sheetIndex = 0; sheetIndex < inspection.sheets().size(); sheetIndex++) {
            FeishuImportXlsxInspector.SheetInspection sheet = inspection.sheets().get(sheetIndex);
            FeishuImportTableCatalog.Match match = matches.get(sheetIndex);
            PreflightTable table = tables.get(sheetIndex);
            tableViews.add(new FeishuImportTablePreview(sheet.sheetName(), match.tableCode(),
                    match.domainCode(), match.objectType(), match.mappingStatus(),
                    sheet.headerRowNumber(), sheet.rowCount(), table.duplicateRows(), sheet.columnCount(),
                    sheet.attachmentReferenceCount(), sheet.headers(), match.attachmentFields(),
                    sheet.sampleRows()));
        }
        List<FeishuImportIssueView> issueViews = new ArrayList<>(inspection.issues().stream()
                .map(FeishuImportBundleService::issueView)
                .toList());
        issueViews.addAll(deduplication.issues());
        appendQualityIssueViews(issueViews, missingSourceDocumentNo,
                "FEISHU_SOURCE_DOCUMENT_NO_MISSING", "来源单号",
                "行未识别到来源单号，后续投影会保留在待映射状态");
        appendQualityIssueViews(issueViews, missingSourceCreatedAt,
                "FEISHU_SOURCE_CREATED_AT_MISSING", "创建时间",
                "行未识别到飞书创建时间，不能按飞书创建时间生成内部编码");
        List<PreflightIssue> issues = issueViews.stream()
                .map(issue -> new PreflightIssue(UUID.randomUUID(), issue.severity(), issue.issueType(),
                        issue.tableName(), issue.rowNumber(), issue.fieldName(), issue.message()))
                .toList();
        String status = status(issueViews);
        store.savePreflight(new PreflightBatch(batchId, caller.tenantId(), caller.userId(),
                SOURCE_SYSTEM, sourceUrl, fileName, copy.bytes(), copy.sha256(), status,
                tableViews.size(), totalRows, deduplication.duplicateRows(), totalAttachments,
                now, tables, issues, rawRows));
        return new FeishuImportPreflightResult(batchId, status, SOURCE_SYSTEM, fileName,
                copy.bytes(), copy.sha256(), sourceUrl, tableViews.size(), totalRows,
                deduplication.duplicateRows(), totalAttachments, now, tableViews, issueViews);
    }

    private static void appendQualityIssueViews(List<FeishuImportIssueView> issues,
                                                Map<String, Long> counts,
                                                String issueType,
                                                String fieldName,
                                                String suffix) {
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            issues.add(issueView("WARN", issueType, entry.getKey(), null,
                    fieldName, entry.getValue() + suffix));
        }
    }

    private Map<UUID, ProjectionDecision> projectionDecisions(CallerIdentity caller, List<StoredRawRow> rows,
                                                              boolean dryRun,
                                                              ProjectionProgressRecorder progressRecorder,
                                                              Map<String, String> crmAreaCodes) {
        Map<UUID, ProjectionDecision> decisions = new HashMap<>();
        List<StoredRawRow> employeeRows = new ArrayList<>();
        List<StoredRawRow> crmAreaRows = new ArrayList<>();
        List<StoredRawRow> crmCustomerRows = new ArrayList<>();
        List<StoredRawRow> erpProductRows = new ArrayList<>();
        List<StoredRawRow> salesOrderRows = new ArrayList<>();
        List<StoredRawRow> salesOrderLineRows = new ArrayList<>();
        List<StoredRawRow> salesPaymentRows = new ArrayList<>();
        List<StoredRawRow> otherRows = new ArrayList<>();
        for (StoredRawRow row : rows) {
            if (isHrEmployeeProjection(row)) {
                employeeRows.add(row);
            } else if (isCrmAreaProjection(row)) {
                crmAreaRows.add(row);
            } else if (isCrmCustomerProjection(row)) {
                crmCustomerRows.add(row);
            } else if (isErpProductProjection(row)) {
                erpProductRows.add(row);
            } else if (isSalesOrderProjection(row)) {
                salesOrderRows.add(row);
            } else if (isSalesOrderLineProjection(row)) {
                salesOrderLineRows.add(row);
            } else if (isSalesPaymentProjection(row)) {
                salesPaymentRows.add(row);
            } else {
                otherRows.add(row);
            }
        }
        log.info("飞书导入投影分组 tenantId={} employees={} crmAreas={} crmCustomers={} erpProducts={} orders={} lines={} payments={} other={}",
                caller.tenantId(), employeeRows.size(), crmAreaRows.size(), crmCustomerRows.size(),
                erpProductRows.size(), salesOrderRows.size(), salesOrderLineRows.size(),
                salesPaymentRows.size(), otherRows.size());
        decisions.putAll(projectEmployees(caller, employeeRows, dryRun));
        decisions.putAll(projectCrmAreas(caller, crmAreaRows, dryRun));
        decisions.putAll(projectCrmCustomers(caller, crmCustomerRows, dryRun));
        decisions.putAll(projectErpProducts(caller, erpProductRows, dryRun));
        FeishuSalesOrderImportMapper.MappingContext salesOrderContext =
                salesOrderMappingContext(caller, rows, decisions, crmAreaCodes);
        SalesOrderProjectionResult salesOrderResult =
                projectSalesOrders(caller, salesOrderRows, salesOrderLineRows, dryRun,
                        salesOrderContext, progressRecorder);
        decisions.putAll(salesOrderResult.decisions());
        decisions.putAll(projectSalesPaymentsFromOrders(caller, salesOrderRows, dryRun,
                salesOrderResult.ordersBySourceNo(), decisions, salesOrderContext, progressRecorder));
        decisions.putAll(projectSalesPayments(caller, salesPaymentRows, dryRun,
                salesOrderResult.ordersBySourceNo(), salesOrderContext, progressRecorder));
        for (StoredRawRow row : otherRows) {
            decisions.put(row.id(), projectionDecision(caller, row, dryRun));
        }
        return decisions;
    }

    private ProjectionDecision projectionDecision(CallerIdentity caller, StoredRawRow row, boolean dryRun) {
        if (row.tableCode() == null || row.domainCode() == null || row.objectType() == null) {
            return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                    null, "FEISHU_TABLE_MAPPING_REQUIRED", "未识别到内部业务域，需要先配置表和字段映射");
        }
        if (isSalesOrderProjection(row)) {
            return projectSalesOrder(caller, row, dryRun,
                    FeishuSalesOrderImportMapper.MappingContext.empty());
        }
        if (isSalesOrderLineProjection(row)) {
            return projectSalesOrderLine(caller, row, dryRun,
                    FeishuSalesOrderImportMapper.MappingContext.empty());
        }
        if (isSalesPaymentProjection(row)) {
            return projectSalesPayment(caller, row, dryRun, Map.of(),
                    FeishuSalesOrderImportMapper.MappingContext.empty());
        }
        if (isHrEmployeeProjection(row)) {
            return projectEmployee(caller, row, dryRun);
        }
        if (isCrmAreaProjection(row)) {
            return projectCrmArea(caller, row, dryRun);
        }
        if (isCrmCustomerProjection(row)) {
            return projectCrmCustomer(caller, row, dryRun);
        }
        if (isErpProductProjection(row)) {
            return projectErpProduct(caller, row, dryRun);
        }
        return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                null, "FEISHU_DOMAIN_PROJECTION_PENDING", "已完成预检落库，等待内部领域投影接入");
    }

    private static void putProjectionDecision(Map<UUID, ProjectionDecision> decisions,
                                              StoredRawRow row,
                                              ProjectionDecision decision,
                                              ProjectionProgressRecorder progressRecorder) {
        if (row == null || decision == null) return;
        decisions.put(row.id(), decision);
        if (progressRecorder != null) {
            progressRecorder.record(row, decision);
        }
    }

    private static void putProjectionDecision(Map<UUID, ProjectionDecision> decisions,
                                              Collection<StoredRawRow> rows,
                                              ProjectionDecision decision,
                                              ProjectionProgressRecorder progressRecorder) {
        if (rows == null) return;
        rows.forEach(row -> putProjectionDecision(decisions, row, decision, progressRecorder));
    }

    private static RowProjectionUpdate rowProjectionUpdate(CallerIdentity caller,
                                                           StoredRawRow row,
                                                           ProjectionDecision decision,
                                                           Instant updatedAt) {
        return new RowProjectionUpdate(caller.tenantId(), row.id(),
                decision.status(), decision.targetDomain(), decision.targetObjectType(),
                decision.targetId(), decision.errorCode(), decision.message(),
                caller.userId(), updatedAt);
    }

    private static List<FeishuImportRunIssueSummaryView> runIssueSummariesFromRows(
            List<StoredRawRow> rows, Map<UUID, ProjectionDecision> decisions) {
        Map<String, RunIssueSummaryAccumulator> summaries = new LinkedHashMap<>();
        for (StoredRawRow row : rows == null ? List.<StoredRawRow>of() : rows) {
            ProjectionDecision decision = decisions == null ? null : decisions.get(row.id());
            String status = firstNonBlank(decision == null ? null : decision.status(),
                    row.projectionStatus(), "PENDING");
            if ("PROJECTED".equals(status) || "SKIPPED".equals(status)) continue;
            addRunIssueSummary(summaries, status,
                    firstNonBlank(decision == null ? null : decision.targetDomain(), row.targetDomain(), row.domainCode()),
                    firstNonBlank(decision == null ? null : decision.targetObjectType(),
                            row.targetObjectType(), row.objectType()),
                    decision == null ? row.errorCode() : decision.errorCode(),
                    decision == null ? row.errorMessage() : decision.message(), 1L);
        }
        return runIssueSummaryViews(summaries);
    }

    private static List<FeishuImportRunIssueSummaryView> runIssueSummariesFromStore(
            List<ProjectionIssueSummary> rows) {
        Map<String, RunIssueSummaryAccumulator> summaries = new LinkedHashMap<>();
        for (ProjectionIssueSummary row : rows == null ? List.<ProjectionIssueSummary>of() : rows) {
            addRunIssueSummary(summaries, row.projectionStatus(), row.targetDomain(),
                    row.targetObjectType(), row.errorCode(), row.errorMessage(), row.rowCount());
        }
        return runIssueSummaryViews(summaries);
    }

    private static void addRunIssueSummary(Map<String, RunIssueSummaryAccumulator> summaries,
                                           String status, String targetDomain,
                                           String targetObjectType, String errorCode,
                                           String errorMessage, long rowCount) {
        if (rowCount <= 0) return;
        String normalizedStatus = firstNonBlank(status, "PENDING");
        String message = compactRunIssueMessage(errorMessage, normalizedStatus);
        String category = runIssueCategory(normalizedStatus, targetDomain, targetObjectType,
                errorCode, message);
        String key = category + "|" + normalizedStatus + "|" + targetDomain + "|" + targetObjectType + "|" + message;
        summaries.computeIfAbsent(key, ignored -> new RunIssueSummaryAccumulator(
                        category, normalizedStatus, targetDomain, targetObjectType, message))
                .add(rowCount);
    }

    private static List<FeishuImportRunIssueSummaryView> runIssueSummaryViews(
            Map<String, RunIssueSummaryAccumulator> summaries) {
        return summaries.values().stream()
                .map(RunIssueSummaryAccumulator::view)
                .sorted((left, right) -> Long.compare(right.rowCount(), left.rowCount()))
                .limit(RUN_ISSUE_SUMMARY_LIMIT)
                .toList();
    }

    private static String compactRunIssueMessage(String message, String status) {
        String text = clean(message);
        if (text == null) return projectionStatusMessage(status);
        if (text.contains("缺少可自动建立的内部映射或必填字段")
                || text.contains("缺少可自动建立的回款映射或必填字段")
                || text.contains("销售订单已识别，但缺少可自动建立的订单明细")
                || text.contains("订单明细已识别，但没有找到已导入的销售订单主表")) {
            return text(text, 220);
        }
        if (text.contains("当前批次包含附件") || text.contains("没有可解析的飞书Base地址")) {
            return "当前批次包含附件，但没有可解析的飞书 Base 地址或附件 token，附件未能下载到 COS";
        }
        if (text.contains("附件未入库") || text.contains("file_token") || text.contains("COS")) {
            return text(text, 220);
        }
        if (text.startsWith("Order销售订单创建 failed status=400")
                || text.startsWith("Order销售订单更新 failed status=400")) {
            return "Order销售订单写入失败，业务字段未通过校验";
        }
        if (text.startsWith("Order销售回款创建 failed status=400")
                || text.startsWith("Order销售回款更新 failed status=400")) {
            return "Order销售回款写入失败，业务字段未通过校验";
        }
        return text(text, 220);
    }

    private static String runIssueCategory(String status, String targetDomain,
                                           String targetObjectType, String errorCode,
                                           String message) {
        String code = firstNonBlank(errorCode, "");
        String text = (code + " " + firstNonBlank(message, "") + " "
                + firstNonBlank(targetDomain, "") + " " + firstNonBlank(targetObjectType, ""))
                .toLowerCase(Locale.ROOT);
        if (text.contains("attachment") || text.contains("附件") || text.contains("凭证")
                || text.contains("file_token") || text.contains("cos")) {
            return "ATTACHMENT";
        }
        if (text.contains("商品") || text.contains("规格") || text.contains("单位")
                || text.contains("product") || text.contains("sku")) {
            return "PRODUCT_MAPPING";
        }
        if (text.contains("客户") || text.contains("门店") || text.contains("商家")
                || text.contains("customer") || "CRM".equals(targetDomain)) {
            return "CUSTOMER_MAPPING";
        }
        if (text.contains("回款") || text.contains("收款") || text.contains("付款")
                || text.contains("payment") || "PAYMENT_RECORD".equals(targetObjectType)) {
            return "PAYMENT_MAPPING";
        }
        if (text.contains("订单明细") || text.contains("order_line")
                || "SALES_ORDER_LINE".equals(targetObjectType)) {
            return "ORDER_LINE";
        }
        if (text.contains("field_mapping") || text.contains("table_mapping") || text.contains("字段映射")) {
            return "FIELD_MAPPING";
        }
        if (text.contains("failed status=400") || "FAILED".equals(status)) {
            return "ORDER_VALIDATION";
        }
        if ("PENDING".equals(status) || "WAITING_MAPPING".equals(status)) {
            return "DEPENDENCY";
        }
        return "OTHER";
    }

    private static boolean isHrEmployeeProjection(StoredRawRow row) {
        return "HR".equals(row.domainCode()) && "EMPLOYEE".equals(row.objectType());
    }

    private static boolean isCrmCustomerProjection(StoredRawRow row) {
        return "CRM".equals(row.domainCode())
                && ("CUSTOMER".equals(row.objectType()) || "STORE".equals(row.objectType()));
    }

    private static boolean isCrmAreaProjection(StoredRawRow row) {
        return "CRM".equals(row.domainCode()) && "REGION".equals(row.objectType());
    }

    private static boolean isErpProductProjection(StoredRawRow row) {
        return "ERP".equals(row.domainCode()) && "PRODUCT".equals(row.objectType());
    }

    private static boolean isSalesOrderProjection(StoredRawRow row) {
        return FeishuSalesOrderImportMapper.ORDER_TABLE_CODE.equals(row.tableCode())
                && "ORDER".equals(row.domainCode())
                && "SALES_ORDER".equals(row.objectType());
    }

    private static boolean isSalesOrderLineProjection(StoredRawRow row) {
        return FeishuSalesOrderImportMapper.ORDER_LINE_TABLE_CODE.equals(row.tableCode())
                && "ORDER".equals(row.domainCode())
                && "SALES_ORDER_LINE".equals(row.objectType());
    }

    private static boolean isSalesPaymentProjection(StoredRawRow row) {
        return FeishuSalesPaymentImportMapper.PAYMENT_TABLE_CODE.equals(row.tableCode())
                && "ORDER".equals(row.domainCode())
                && "PAYMENT_RECORD".equals(row.objectType());
    }

    private FeishuSalesOrderImportMapper.MappingContext salesOrderMappingContext(
            CallerIdentity caller, List<StoredRawRow> rows, Map<UUID, ProjectionDecision> decisions,
            Map<String, String> crmAreaCodes) {
        SalesOrderMappingContext context = new SalesOrderMappingContext(crmAreaCodes);
        for (StoredRawRow row : rows) {
            ProjectionDecision decision = decisions.get(row.id());
            if (decision == null || !"PROJECTED".equals(decision.status())) continue;
            if ("CRM".equals(decision.targetDomain())
                    && ("CUSTOMER".equals(decision.targetObjectType())
                    || "STORE".equals(decision.targetObjectType()))) {
                context.addCustomer(row, decision);
            } else if ("ERP".equals(decision.targetDomain())
                    && "PRODUCT".equals(decision.targetObjectType())) {
                context.addProduct(row, decision);
            }
        }
        resolveEmployeesFromHr(caller, rows, context);
        resolveOrderCustomersFromCrm(caller, rows, context);
        resolveOrderProductsFromErp(caller, rows, context);
        return context;
    }

    private void resolveEmployeesFromHr(
            CallerIdentity caller, List<StoredRawRow> rows, SalesOrderMappingContext context) {
        if (hrEmployeeProjectionClient == null || rows == null || rows.isEmpty()) return;
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (StoredRawRow row : rows) {
            if (isSalesOrderProjection(row)) {
                putEmployeeNameCandidate(names, ownerNameFromSalesOrder(row));
                putEmployeeNameCandidate(names, paymentCollectorName(row));
            } else if (isSalesPaymentProjection(row)) {
                putEmployeeNameCandidate(names, paymentCollectorName(row));
            }
        }
        if (names.isEmpty()) return;
        CallerIdentity serviceCaller = hrProjectionCaller(caller.tenantId());
        List<String> employeeNames = new ArrayList<>(names.values());
        log.info("飞书HR员工解析开始 tenantId={} uniqueNames={}",
                caller.tenantId(), employeeNames.size());
        for (int start = 0; start < employeeNames.size(); start += DOMAIN_PROJECTION_BATCH_SIZE) {
            List<String> chunk = employeeNames.subList(start,
                    Math.min(start + DOMAIN_PROJECTION_BATCH_SIZE, employeeNames.size()));
            log.info("飞书HR员工解析批次 tenantId={} start={} size={}",
                    caller.tenantId(), start, chunk.size());
            try {
                List<ExternalEmployeeResolvedView> resolved = hrEmployeeProjectionClient.resolve(
                        serviceCaller,
                        new ExternalEmployeeResolveCommand(SOURCE_SYSTEM, FeishuStaffImportMapper.TABLE_CODE,
                                List.of(), chunk));
                for (ExternalEmployeeResolvedView item : resolved == null
                        ? List.<ExternalEmployeeResolvedView>of()
                        : resolved) {
                    context.addResolvedEmployee(item);
                }
            } catch (RuntimeException exception) {
                log.warn("飞书HR员工解析失败 tenantId={} rows={} error={}",
                        caller.tenantId(), chunk.size(), clean(exception.getMessage()));
            }
        }
        log.info("飞书HR员工解析完成 tenantId={} resolvedNames={}",
                caller.tenantId(), context.employees.size());
    }

    private static void putEmployeeNameCandidate(Map<String, String> names, String value) {
        if (names == null) return;
        String candidate = singleEmployeeNameCandidate(value);
        if (candidate != null) names.putIfAbsent(candidate, candidate);
    }

    private static String singleEmployeeNameCandidate(String value) {
        String text = clean(value);
        if (text == null) return null;
        String repeated = repeatedSingleToken(text);
        if (repeated != null) return repeated;
        return text.matches(".*\\s+.*") ? null : text;
    }

    private static String repeatedSingleToken(String value) {
        String text = clean(value);
        if (text == null || !text.matches(".*\\s+.*")) return null;
        String[] parts = text.split("\\s+");
        if (parts.length < 2) return null;
        String first = parts[0];
        for (String part : parts) {
            if (!first.equals(part)) return null;
        }
        return first;
    }

    private void resolveOrderCustomersFromCrm(
            CallerIdentity caller, List<StoredRawRow> rows, SalesOrderMappingContext context) {
        if (crmCustomerProjectionClient == null || rows == null || rows.isEmpty()) return;
        LinkedHashMap<String, CommandRow<ExternalCrmCustomerRowCommand>> commandsBySource = new LinkedHashMap<>();
        for (StoredRawRow row : rows) {
            if (!isSalesOrderProjection(row)) continue;
            ExternalCrmCustomerRowCommand command = crmCustomerCommandFromSalesOrder(row, context);
            if (command == null) continue;
            String key = command.sourceTenantKey() + "\u0001" + command.sourceCustomerId();
            commandsBySource.putIfAbsent(key, new CommandRow<>(row, command));
        }
        if (commandsBySource.isEmpty()) return;
        CallerIdentity serviceCaller = crmProjectionCaller(caller.tenantId());
        List<CommandRow<ExternalCrmCustomerRowCommand>> commandRows = new ArrayList<>(commandsBySource.values());
        log.info("飞书销售订单CRM客户解析开始 tenantId={} uniqueCustomers={}",
                caller.tenantId(), commandRows.size());
        for (int start = 0; start < commandRows.size(); start += DOMAIN_PROJECTION_BATCH_SIZE) {
            List<CommandRow<ExternalCrmCustomerRowCommand>> chunk = commandRows.subList(start,
                    Math.min(start + DOMAIN_PROJECTION_BATCH_SIZE, commandRows.size()));
            log.info("飞书销售订单CRM客户解析批次 tenantId={} start={} size={}",
                    caller.tenantId(), start, chunk.size());
            try {
                ExternalCrmCustomerSyncResult result = crmCustomerProjectionClient.sync(serviceCaller, SOURCE_SYSTEM,
                        chunk.stream().map(CommandRow::command).toList());
                Map<String, ExternalCrmCustomerSyncRowResult> resultBySource = new HashMap<>();
                (result.rows() == null ? List.<ExternalCrmCustomerSyncRowResult>of() : result.rows())
                        .forEach(item -> resultBySource.putIfAbsent(item.sourceCustomerId(), item));
                for (CommandRow<ExternalCrmCustomerRowCommand> item : chunk) {
                    ExternalCrmCustomerSyncRowResult rowResult = resultBySource.get(
                            item.command().sourceCustomerId());
                    if (rowResult == null || "FAILED".equals(rowResult.status()) || rowResult.customerId() == null) {
                        continue;
                    }
                    context.addResolvedCustomer(item.row(), item.command(), rowResult);
                }
            } catch (RuntimeException exception) {
                log.warn("飞书销售订单CRM客户解析失败 tenantId={} rows={} error={}",
                        caller.tenantId(), chunk.size(), clean(exception.getMessage()));
            }
        }
        log.info("飞书销售订单CRM客户解析完成 tenantId={} resolvedCustomers={}",
                caller.tenantId(), context.customers.size());
    }

    private ExternalCrmCustomerRowCommand crmCustomerCommandFromSalesOrder(
            StoredRawRow row, SalesOrderMappingContext context) {
        if (row == null) return null;
        Map<String, String> values = row.values();
        String storeRef = value(values, "关联门店", "门店编码", "客户编码", "商家编号", "订单编号门店");
        String sourceCustomerId = firstNonBlank(
                sourceCodePrefix(storeRef),
                sourceCodeCandidate(value(values, "门店编码", "客户编码", "商家编号")),
                sourceCodePrefix(value(values, "订单编号门店")),
                sourceCodePrefix(row.sourceDocumentNo()));
        String customerName = firstNonBlank(
                linkedDisplayName(storeRef),
                value(values, "门店", "门店名称", "客户名称", "客户"),
                linkedDisplayName(value(values, "订单编号门店")),
                nonCodeDescriptor(storeRef));
        if (sourceCustomerId == null || customerName == null) return null;
        Instant sourceCreatedAt = row.sourceCreatedAt() == null
                ? FeishuSalesOrderImportMapper.sourceCreatedAt(values)
                : row.sourceCreatedAt();
        if (sourceCreatedAt == null) return null;
        Map<String, String> payload = crmCustomerPayloadFromSalesOrder(row, sourceCustomerId, customerName);
        String ownerName = ownerNameFromSalesOrder(row);
        FeishuSalesOrderImportMapper.EmployeeMapping employee = context == null
                ? null
                : context.employee(ownerName).orElse(null);
        String ownerEmployeeCode = firstNonBlank(value(values, "销售员工编码", "销售工号"),
                employee == null ? null : employee.employeeCode());
        String ownerEmployeeName = firstNonBlank(ownerName, employee == null ? null : employee.employeeName());
        return new ExternalCrmCustomerRowCommand(
                null,
                FeishuCrmCustomerImportMapper.STORE_TABLE_CODE,
                text(sourceCustomerId, 128),
                text(firstNonBlank(storeRef, sourceCustomerId), 128),
                text(customerName, 200),
                text(value(values, "联系人", "收货人"), 100),
                text(value(values, "联系电话", "手机号", "电话"), 50),
                row.sheetName(),
                text(value(values, "客户类型", "门店类型", "门店属性", "商家类型"), 120),
                text(value(values, "所属地区", "销售区域", "区域", "归属地区"), 80),
                text(value(values, "市", "城市", "意向城市"), 80),
                text(value(values, "客户地址", "门店地址", "收货地址", "地址"), 1000),
                text(ownerEmployeeCode, 50),
                text(ownerEmployeeName, 100),
                null,
                text(value(values, "合作状态", "门店状态", "状态"), 80),
                sourceCreatedAt,
                firstNonNullInstant(parseInstant(value(values, "修改时间", "更新时间")), sourceCreatedAt),
                rowHash(payload),
                payloadJson(payload));
    }

    private static Map<String, String> crmCustomerPayloadFromSalesOrder(
            StoredRawRow row, String sourceCustomerId, String customerName) {
        Map<String, String> values = row.values();
        Map<String, String> payload = new LinkedHashMap<>();
        putIfPresent(payload, "sourceCustomerId", sourceCustomerId);
        putIfPresent(payload, "customerName", customerName);
        putIfPresent(payload, "storeReference", value(values, "关联门店", "门店编码", "订单编号门店"));
        putIfPresent(payload, "cityName", value(values, "市", "城市", "意向城市"));
        putIfPresent(payload, "regionName", value(values, "所属地区", "销售区域", "区域", "归属地区"));
        putIfPresent(payload, "ownerEmployeeNameSnapshot", value(values, "销售", "业务员", "销售人员"));
        putIfPresent(payload, "sourceOrderNo", row.sourceDocumentNo());
        return payload;
    }

    private void resolveOrderProductsFromErp(
            CallerIdentity caller, List<StoredRawRow> rows, SalesOrderMappingContext context) {
        if (erpProductProjectionClient == null || rows == null || rows.isEmpty()) return;
        LinkedHashMap<String, StoredRawRow> rowsByReference = new LinkedHashMap<>();
        List<ExternalProductResolveRowCommand> commands = new ArrayList<>();
        for (StoredRawRow row : rows) {
            ExternalProductResolveRowCommand command = productResolveCommand(row);
            if (command == null) continue;
            rowsByReference.put(command.referenceId(), row);
            commands.add(command);
        }
        if (commands.isEmpty()) return;
        CallerIdentity serviceCaller = erpProjectionCaller(caller.tenantId());
        log.info("飞书销售订单ERP商品解析开始 tenantId={} uniqueProducts={}",
                caller.tenantId(), commands.size());
        for (int start = 0; start < commands.size(); start += DOMAIN_PROJECTION_BATCH_SIZE) {
            List<ExternalProductResolveRowCommand> chunk = commands.subList(start,
                    Math.min(start + DOMAIN_PROJECTION_BATCH_SIZE, commands.size()));
            log.info("飞书销售订单ERP商品解析批次 tenantId={} start={} size={}",
                    caller.tenantId(), start, chunk.size());
            try {
                List<ExternalProductResolvedView> resolved = erpProductProjectionClient.resolve(
                        serviceCaller, ExternalSourceCodes.DOMAIN_DINGHUOBAO, chunk);
                for (ExternalProductResolvedView item : resolved == null
                        ? List.<ExternalProductResolvedView>of()
                        : resolved) {
                    if (item == null || !"MATCHED".equals(item.status())) continue;
                    StoredRawRow row = rowsByReference.get(item.referenceId());
                    if (row != null) context.addResolvedProduct(row, item);
                }
            } catch (RuntimeException exception) {
                log.warn("飞书销售订单商品解析失败 tenantId={} rows={} error={}",
                        caller.tenantId(), chunk.size(), clean(exception.getMessage()));
            }
        }
        log.info("飞书销售订单ERP商品解析完成 tenantId={} resolvedProducts={}",
                caller.tenantId(), context.products.size());
    }

    private static ExternalProductResolveRowCommand productResolveCommand(StoredRawRow row) {
        if (row == null || (!isSalesOrderProjection(row) && !isSalesOrderLineProjection(row))) {
            return null;
        }
        Map<String, String> values = row.values();
        String explicitProductRef = value(values, "产品编号", "商品编码", "产品编码", "SKU编码",
                "商品编号", "产品编码名称", "产品编号名称");
        String orderProductRef = value(values, "订单产品", "产品名称", "商品名称", "产品", "商品");
        if (hasMultipleValues(orderProductRef)) return null;
        String productCode = firstNonBlank(
                sourceCodeCandidate(value(values, "商品编码", "产品编码", "商品编号")),
                sourceCodePrefix(explicitProductRef));
        String variantCode = sourceCodeCandidate(value(values, "SKU编码", "规格编码", "skuCode"));
        String productName = firstNonBlank(descriptorProductName(orderProductRef),
                linkedDisplayName(orderProductRef),
                nonCodeDescriptor(orderProductRef),
                descriptorProductName(explicitProductRef),
                linkedDisplayName(explicitProductRef),
                nonCodeDescriptor(explicitProductRef));
        String specification = value(values, "规格", "规格名称", "规格描述", "产品规格");
        if (firstNonBlank(productCode, variantCode, productName) == null) return null;
        return new ExternalProductResolveRowCommand(row.id().toString(), productCode, variantCode,
                productName, specification);
    }

    private static String ownerNameFromSalesOrder(StoredRawRow row) {
        return row == null ? null : value(row.values(), "销售", "业务员", "销售人员");
    }

    private static String paymentCollectorName(StoredRawRow row) {
        return row == null
                ? null
                : value(row.values(), "回款人", "收款人", "销售", "业务员", "销售人员");
    }

    private SalesOrderProjectionResult projectSalesOrders(
            CallerIdentity caller,
            List<StoredRawRow> salesOrderRows,
            List<StoredRawRow> salesOrderLineRows,
            boolean dryRun,
            FeishuSalesOrderImportMapper.MappingContext mappingContext,
            ProjectionProgressRecorder progressRecorder) {
        Map<UUID, ProjectionDecision> decisions = new HashMap<>();
        Map<String, SalesOrderDetailView> ordersBySourceNo = new HashMap<>();
        if (salesOrderRows.isEmpty() && salesOrderLineRows.isEmpty()) {
            return new SalesOrderProjectionResult(decisions, ordersBySourceNo);
        }
        FeishuSalesOrderImportMapper.MappingContext context = mappingContext == null
                ? FeishuSalesOrderImportMapper.MappingContext.empty()
                : mappingContext;
        LinkedHashMap<String, SalesOrderProjectionGroup> groups = new LinkedHashMap<>();
        Set<String> skippedReturnSourceNos = new HashSet<>();
        for (StoredRawRow row : salesOrderRows) {
            FeishuSalesOrderImportMapper.OrderHeaderPlan plan = salesOrderImportMapper.headerPlan(row, context);
            if (plan.command() == null) {
                if ("FEISHU_ORDER_RETURN_QUANTITY_ZERO_SKIPPED".equals(plan.errorCode())
                        && plan.sourceOrderNo() != null) {
                    skippedReturnSourceNos.add(plan.sourceOrderNo());
                }
                putProjectionDecision(decisions, row,
                        new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                                null, plan.errorCode(), plan.message()),
                        progressRecorder);
                continue;
            }
            group(groups, plan.sourceOrderNo()).addHeader(row, plan.command());
        }
        for (StoredRawRow row : salesOrderLineRows) {
            FeishuSalesOrderImportMapper.LineProjectionPlan plan = salesOrderImportMapper.linePlan(row, context);
            if (plan.command() == null) {
                putProjectionDecision(decisions, row,
                        new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                                null, plan.errorCode(), plan.message()),
                        progressRecorder);
                continue;
            }
            if (skippedReturnSourceNos.contains(plan.parentSourceOrderNo())) {
                putProjectionDecision(decisions, row,
                        new ProjectionDecision("SKIPPED", row.domainCode(), row.objectType(),
                                null, "FEISHU_ORDER_RETURN_QUANTITY_ZERO_SKIPPED",
                                "飞书订单数量为0，关联明细按退货/冲销类单据跳过业务订单投影"),
                        progressRecorder);
                continue;
            }
            group(groups, plan.parentSourceOrderNo()).addLine(row, plan);
        }
        for (SalesOrderProjectionGroup group : groups.values()) {
            if (group.lineRows().isEmpty() && group.headerCommand() != null) {
                addInlineOrderLine(group, context, decisions, progressRecorder);
            }
            if (group.lineRows().isEmpty() && group.headerCommand() != null) {
                ProjectionDecision headerOnlyDecision;
                if (dryRun) {
                    headerOnlyDecision = new ProjectionDecision("SKIPPED", "ORDER", "SALES_ORDER",
                            null, null, "试跑通过，正式写入时会按待完善订单导入Order");
                } else if (orderSalesOrderProjectionClient == null) {
                    headerOnlyDecision = new ProjectionDecision("WAITING_MAPPING", "ORDER", "SALES_ORDER",
                            null, "FEISHU_ORDER_PROJECTION_CLIENT_REQUIRED", "Order投影客户端未装配");
                } else {
                    SalesOrderWriteResult orderWrite = writeSalesOrder(caller, group.headerCommand());
                    ProjectionDecision orderDecision = orderWrite.decision();
                    if ("PROJECTED".equals(orderDecision.status()) && orderWrite.detail() != null) {
                        ordersBySourceNo.put(group.sourceOrderNo(), orderWrite.detail());
                    }
                    headerOnlyDecision = new ProjectionDecision(orderDecision.status(), "ORDER", "SALES_ORDER",
                            orderDecision.targetId(), orderDecision.errorCode(),
                            appendMessage(orderDecision.message(), "缺少可自动建立的订单明细，已按待完善订单落库"));
                }
                group.headerRows().forEach(row ->
                        putProjectionDecision(decisions, row, headerOnlyDecision, progressRecorder));
                continue;
            }
            if (group.lineRows().isEmpty()) {
                continue;
            }
            List<SalesOrderLineCommand> lines = mergedSalesOrderLines(group.lineRows());
            if (lines.size() > 200) {
                group.rows().forEach(row -> putProjectionDecision(decisions, row,
                        new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                                null, "FEISHU_ORDER_LINE_LIMIT_EXCEEDED",
                                "同一销售订单合并后仍超过200个商品规格，需要拆分订单或补充业务处理规则"),
                        progressRecorder));
                continue;
            }
            if (group.headerCommand() == null) {
                if (dryRun) {
                    group.lineRows().forEach(item -> putProjectionDecision(decisions, item.row(),
                            new ProjectionDecision("SKIPPED", "ORDER", "SALES_ORDER_LINE",
                                    null, null, "试跑通过，正式写入时会按来源订单号补入已存在销售订单"),
                            progressRecorder));
                    continue;
                }
                if (orderSalesOrderProjectionClient == null) {
                    group.lineRows().forEach(item -> putProjectionDecision(decisions, item.row(),
                            new ProjectionDecision("WAITING_MAPPING", "ORDER", "SALES_ORDER_LINE",
                                    null, "FEISHU_ORDER_PROJECTION_CLIENT_REQUIRED", "Order投影客户端未装配"),
                            progressRecorder));
                    continue;
                }
                SalesOrderWriteResult lineWrite = writeSalesOrderLinesToExistingOrder(
                        caller, group.sourceOrderNo(), lines);
                ProjectionDecision lineDecision = lineWrite.decision();
                if (lineWrite.detail() != null) {
                    ordersBySourceNo.put(group.sourceOrderNo(), lineWrite.detail());
                }
                group.lineRows().forEach(item -> putProjectionDecision(decisions, item.row(),
                        new ProjectionDecision(lineDecision.status(), "ORDER", "SALES_ORDER_LINE",
                                lineDecision.targetId(), lineDecision.errorCode(), lineDecision.message()),
                        progressRecorder));
                continue;
            }
            if (dryRun) {
                group.headerRows().forEach(row -> putProjectionDecision(decisions, row,
                        new ProjectionDecision("SKIPPED", "ORDER", "SALES_ORDER",
                                null, null, "试跑通过，正式写入时会导入Order销售订单"),
                        progressRecorder));
                Set<UUID> headerRowIds = headerRowIds(group);
                group.lineRows().forEach(item -> {
                    ProjectionDecision decision = headerRowIds.contains(item.row().id())
                            ? decisions.get(item.row().id())
                            : new ProjectionDecision("SKIPPED", "ORDER", "SALES_ORDER_LINE",
                            null, null, "试跑通过，正式写入时会随销售订单导入明细");
                    putProjectionDecision(decisions, item.row(), decision, progressRecorder);
                });
                continue;
            }
            if (orderSalesOrderProjectionClient == null) {
                group.rows().forEach(row -> putProjectionDecision(decisions, row,
                        new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                                null, "FEISHU_ORDER_PROJECTION_CLIENT_REQUIRED", "Order投影客户端未装配"),
                        progressRecorder));
                continue;
            }
            SalesOrderCommand command = salesOrderCommandForGroup(group, lines);
            SalesOrderWriteResult orderWrite = writeSalesOrder(caller, command);
            ProjectionDecision orderDecision = orderWrite.decision();
            if ("PROJECTED".equals(orderDecision.status()) && orderWrite.detail() != null) {
                ordersBySourceNo.put(group.sourceOrderNo(), orderWrite.detail());
            }
            group.headerRows().forEach(row ->
                    putProjectionDecision(decisions, row, orderDecision, progressRecorder));
            Set<UUID> headerRowIds = headerRowIds(group);
            group.lineRows().forEach(item -> {
                ProjectionDecision decision = headerRowIds.contains(item.row().id())
                        ? decisions.get(item.row().id())
                        : new ProjectionDecision(orderDecision.status(), "ORDER", "SALES_ORDER_LINE",
                        orderDecision.targetId(), orderDecision.errorCode(),
                        "PROJECTED".equals(orderDecision.status())
                                ? "飞书订单明细已随销售订单导入Order"
                                : orderDecision.message());
                putProjectionDecision(decisions, item.row(), decision, progressRecorder);
            });
        }
        return new SalesOrderProjectionResult(decisions, ordersBySourceNo);
    }

    private void addInlineOrderLine(SalesOrderProjectionGroup group,
                                    FeishuSalesOrderImportMapper.MappingContext context,
                                    Map<UUID, ProjectionDecision> decisions,
                                    ProjectionProgressRecorder progressRecorder) {
        for (StoredRawRow row : group.headerRows()) {
            FeishuSalesOrderImportMapper.LineProjectionPlan plan = salesOrderImportMapper.inlineOrderLinePlan(row, context);
            if (plan.command() == null) {
                putProjectionDecision(decisions, row,
                        new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                                null, plan.errorCode(), plan.message()),
                        progressRecorder);
            } else {
                group.addLine(row, plan);
            }
        }
    }

    private static SalesOrderProjectionGroup group(Map<String, SalesOrderProjectionGroup> groups,
                                                   String sourceOrderNo) {
        return groups.computeIfAbsent(sourceOrderNo, SalesOrderProjectionGroup::new);
    }

    private static Set<UUID> headerRowIds(SalesOrderProjectionGroup group) {
        return group.headerRows().stream()
                .map(StoredRawRow::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<SalesOrderLineCommand> mergedSalesOrderLines(List<SalesOrderLineProjection> lineRows) {
        LinkedHashMap<String, SalesOrderLineAccumulator> lines = new LinkedHashMap<>();
        for (SalesOrderLineProjection lineRow : lineRows) {
            SalesOrderLineCommand line = lineRow.plan().command();
            String key = line.productId() != null && line.productVariantId() != null
                    ? line.productId() + "::" + line.productVariantId()
                    : "INCOMPLETE::" + firstNonBlank(line.productNameSnapshot(),
                            lineRow.plan().sourceLineNo(), lineRow.row().id().toString());
            lines.computeIfAbsent(key, ignored -> new SalesOrderLineAccumulator(line))
                    .add(line, lineRow.plan().lineAmount());
        }
        return lines.values().stream()
                .map(SalesOrderLineAccumulator::command)
                .toList();
    }

    private static SalesOrderCommand salesOrderCommandForGroup(SalesOrderProjectionGroup group,
                                                               List<SalesOrderLineCommand> lines) {
        SalesOrderCommand command = FeishuSalesOrderImportMapper.commandWithLines(group.headerCommand(), lines);
        if (lineRowsOnlyFromHeaderRows(group)) {
            command = salesOrderCommandWithDiscount(command, null, null);
        }
        BigDecimal headerPayableAmount = group.headerRows().stream()
                .map(row -> FeishuSalesOrderImportMapper.headerPayableAmount(row.values()))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (headerPayableAmount == null || lines == null || lines.isEmpty()) return command;
        SalesOrderHeaderAmounts currentAmounts = salesOrderHeaderAmounts(
                lines, command.discountRate(), command.discountAmount());
        if (compare(currentAmounts.payableAmount(), headerPayableAmount) == 0) return command;
        if (headerPayableAmount.compareTo(currentAmounts.payableAmount()) > 0) {
            return salesOrderCommandWithReducedDiscount(command,
                    headerPayableAmount.subtract(currentAmounts.payableAmount()),
                    headerPayableAmount, currentAmounts.payableAmount());
        }
        BigDecimal targetOrderDiscountAmount = currentAmounts.payableAmount().subtract(headerPayableAmount)
                .add(amount(command.discountAmount()));
        if (targetOrderDiscountAmount.compareTo(BigDecimal.ZERO) < 0) return command;
        if (compare(targetOrderDiscountAmount, command.discountAmount()) == 0) return command;
        return salesOrderCommandWithOrderDiscount(command, targetOrderDiscountAmount,
                headerPayableAmount, currentAmounts.payableAmount());
    }

    private static SalesOrderCommand salesOrderCommandWithReducedDiscount(SalesOrderCommand command,
                                                                          BigDecimal payableIncrease,
                                                                          BigDecimal headerPayableAmount,
                                                                          BigDecimal currentPayableAmount) {
        BigDecimal remaining = payableIncrease;
        BigDecimal orderDiscountAmount = amount(command.discountAmount());
        if (orderDiscountAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal reduction = orderDiscountAmount.min(remaining);
            orderDiscountAmount = orderDiscountAmount.subtract(reduction);
            remaining = remaining.subtract(reduction);
        }
        List<SalesOrderLineCommand> adjustedLines = new ArrayList<>();
        for (SalesOrderLineCommand line : command.lines()) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                adjustedLines.add(line);
                continue;
            }
            BigDecimal lineDiscountAmount = amount(line.discountAmount());
            BigDecimal reduction = lineDiscountAmount.min(remaining);
            adjustedLines.add(salesOrderLineCommandWithDiscount(line,
                    nullableZero(line.discountAmount(), lineDiscountAmount.subtract(reduction))));
            remaining = remaining.subtract(reduction);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) return command;
        return salesOrderCommandWithAmounts(command, adjustedLines,
                nullableZero(command.discountAmount(), orderDiscountAmount),
                headerPayableAmount, currentPayableAmount);
    }

    private static boolean lineRowsOnlyFromHeaderRows(SalesOrderProjectionGroup group) {
        if (group == null || group.lineRows().isEmpty()) return false;
        Set<UUID> headerRowIds = headerRowIds(group);
        return !headerRowIds.isEmpty()
                && group.lineRows().stream().allMatch(item -> headerRowIds.contains(item.row().id()));
    }

    private static SalesOrderCommand salesOrderCommandWithDiscount(
            SalesOrderCommand command, BigDecimal discountRate, BigDecimal discountAmount) {
        return new SalesOrderCommand(
                command.customerId(),
                command.sourceSystemCode(),
                command.sourceOrderNo(),
                command.sourceStatusCode(),
                command.sourceCreatorId(),
                command.sourceCreatorStaffCode(),
                command.sourceCreatorName(),
                command.customerCodeSnapshot(),
                command.customerNameSnapshot(),
                command.contactNameSnapshot(),
                command.contactPhoneSnapshot(),
                command.regionCode(),
                command.ownerSalesUserId(),
                command.ownerSalesName(),
                command.ownerEmployeeCode(),
                command.ownerEmployeeNameSnapshot(),
                command.orderDate(),
                command.orderTypeCode(),
                command.paymentMethodCode(),
                command.paymentVoucherKeys(),
                command.sourceUnpaidAmount(),
                discountRate,
                discountAmount,
                command.remark(),
                command.lines(),
                command.submit(),
                command.revision());
    }

    private static SalesOrderCommand salesOrderCommandWithOrderDiscount(SalesOrderCommand command,
                                                                        BigDecimal discountAmount,
                                                                        BigDecimal headerPayableAmount,
                                                                        BigDecimal linePayableAmount) {
        return salesOrderCommandWithAmounts(command, command.lines(), discountAmount,
                headerPayableAmount, linePayableAmount);
    }

    private static SalesOrderCommand salesOrderCommandWithAmounts(SalesOrderCommand command,
                                                                  List<SalesOrderLineCommand> lines,
                                                                  BigDecimal discountAmount,
                                                                  BigDecimal headerPayableAmount,
                                                                  BigDecimal linePayableAmount) {
        String message = "飞书订单表实际小计" + moneyLabel(headerPayableAmount)
                + "与订单明细合计" + moneyLabel(linePayableAmount)
                + "不一致，已按订单表实际小计校准";
        return new SalesOrderCommand(
                command.customerId(),
                command.sourceSystemCode(),
                command.sourceOrderNo(),
                command.sourceStatusCode(),
                command.sourceCreatorId(),
                command.sourceCreatorStaffCode(),
                command.sourceCreatorName(),
                command.customerCodeSnapshot(),
                command.customerNameSnapshot(),
                command.contactNameSnapshot(),
                command.contactPhoneSnapshot(),
                command.regionCode(),
                command.ownerSalesUserId(),
                command.ownerSalesName(),
                command.ownerEmployeeCode(),
                command.ownerEmployeeNameSnapshot(),
                command.orderDate(),
                command.orderTypeCode(),
                command.paymentMethodCode(),
                command.paymentVoucherKeys(),
                command.sourceUnpaidAmount(),
                null,
                discountAmount,
                appendMessage(command.remark(), message),
                lines,
                command.submit(),
                command.revision());
    }

    private static SalesOrderLineCommand salesOrderLineCommandWithDiscount(
            SalesOrderLineCommand line, BigDecimal discountAmount) {
        return new SalesOrderLineCommand(line.productId(), line.productVariantId(),
                line.productCodeSnapshot(), line.skuCodeSnapshot(), line.productNameSnapshot(),
                line.specificationSnapshot(), line.unitCode(), line.quantity(), line.unitPrice(),
                line.discountRate(), discountAmount, line.remark());
    }

    private static BigDecimal nullableZero(BigDecimal original, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) != 0) return value;
        return original == null ? null : BigDecimal.ZERO;
    }

    private static String moneyLabel(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private SalesOrderWriteResult writeSalesOrderLinesToExistingOrder(CallerIdentity caller, String sourceOrderNo,
                                                                      List<SalesOrderLineCommand> lines) {
        CallerIdentity serviceCaller = orderProjectionCaller(caller.tenantId());
        try {
            Optional<SalesOrderDetailView> existing = orderSalesOrderProjectionClient.findSalesOrderBySource(
                    serviceCaller, SOURCE_SYSTEM, sourceOrderNo);
            if (existing.isEmpty()) {
                return new SalesOrderWriteResult(new ProjectionDecision("WAITING_MAPPING", "ORDER",
                        "SALES_ORDER_LINE", null, "FEISHU_ORDER_HEADER_REQUIRED",
                        "订单明细已识别，但没有找到已导入的销售订单主表：" + sourceOrderNo), null);
            }
            SalesOrderDetailView current = existing.get();
            if (sameSalesOrderLines(lines, current.lines()) && sameSalesOrderHeaderAmounts(lines, current)) {
                return new SalesOrderWriteResult(new ProjectionDecision("SKIPPED", "ORDER",
                        "SALES_ORDER_LINE", String.valueOf(current.id()), null,
                        "飞书订单明细已存在且无变化，跳过重复写入"), current);
            }
            SalesOrderCommand command = salesOrderCommandFromExisting(current, lines);
            SalesOrderDetailView detail = orderSalesOrderProjectionClient.updateSalesOrder(
                    serviceCaller, current.id(), command);
            return new SalesOrderWriteResult(new ProjectionDecision("PROJECTED", "ORDER",
                    "SALES_ORDER_LINE", String.valueOf(detail.id()), null,
                    "飞书订单明细已补充到已存在销售订单"), detail);
        } catch (RuntimeException exception) {
            return new SalesOrderWriteResult(new ProjectionDecision("FAILED", "ORDER", "SALES_ORDER_LINE",
                    null, "FEISHU_ORDER_LINE_PROJECT_FAILED", clean(exception.getMessage())), null);
        }
    }

    private static SalesOrderCommand salesOrderCommandFromExisting(SalesOrderDetailView current,
                                                                   List<SalesOrderLineCommand> lines) {
        return new SalesOrderCommand(
                current.customerId(),
                current.sourceSystemCode(),
                current.sourceOrderNo(),
                current.sourceStatusCode(),
                current.sourceCreatorId(),
                current.sourceCreatorStaffCode(),
                current.sourceCreatorName(),
                current.customerCodeSnapshot(),
                current.customerNameSnapshot(),
                current.contactNameSnapshot(),
                current.contactPhoneSnapshot(),
                current.regionCode(),
                current.ownerSalesUserId(),
                current.ownerSalesName(),
                current.ownerEmployeeCode(),
                current.ownerEmployeeNameSnapshot(),
                current.orderDate(),
                current.orderTypeCode(),
                current.paymentMethodCode(),
                current.paymentVoucherKeys(),
                null,
                current.discountRate(),
                current.discountAmount(),
                current.remark(),
                lines,
                false,
                current.revision());
    }

    private static boolean sameSalesOrderLines(List<SalesOrderLineCommand> expected,
                                               List<SalesOrderLineView> actual) {
        List<SalesOrderLineCommand> left = expected == null ? List.of() : expected;
        List<SalesOrderLineView> right = actual == null ? List.of() : actual;
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            SalesOrderLineCommand command = left.get(index);
            SalesOrderLineView current = right.get(index);
            if (!java.util.Objects.equals(command.productId(), current.productId())
                    || !java.util.Objects.equals(command.productVariantId(), current.productVariantId())
                    || compare(command.quantity(), current.quantity()) != 0
                    || compare(command.unitPrice(), current.unitPrice()) != 0
                    || compare(command.discountAmount(), current.discountAmount()) != 0
                    || !java.util.Objects.equals(command.productNameSnapshot(), current.productNameSnapshot())
                    || !java.util.Objects.equals(command.specificationSnapshot(), current.specificationSnapshot())
                    || !java.util.Objects.equals(command.unitCode(), current.unitCode())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSalesOrderHeaderAmounts(List<SalesOrderLineCommand> expected,
                                                       SalesOrderDetailView actual) {
        SalesOrderHeaderAmounts amounts = salesOrderHeaderAmounts(expected, actual.discountRate(), actual.discountAmount());
        return compare(amounts.totalQuantity(), actual.totalQuantity()) == 0
                && compare(amounts.originalAmount(), actual.originalAmount()) == 0
                && compare(amounts.discountAmount(), actual.discountAmount()) == 0
                && compare(amounts.payableAmount(), actual.payableAmount()) == 0;
    }

    private static SalesOrderHeaderAmounts salesOrderHeaderAmounts(List<SalesOrderLineCommand> lines,
                                                                   BigDecimal discountRate,
                                                                   BigDecimal discountAmount) {
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal originalAmount = BigDecimal.ZERO;
        BigDecimal linePayableAmount = BigDecimal.ZERO;
        for (SalesOrderLineCommand line : lines == null ? List.<SalesOrderLineCommand>of() : lines) {
            if (line == null) continue;
            BigDecimal quantity = amount(line.quantity());
            BigDecimal unitPrice = amount(line.unitPrice());
            BigDecimal originalLineAmount = quantity.multiply(unitPrice);
            BigDecimal lineDiscountAmount = amount(line.discountAmount());
            if (lineDiscountAmount.compareTo(BigDecimal.ZERO) == 0 && line.discountRate() != null) {
                lineDiscountAmount = originalLineAmount.multiply(line.discountRate());
            }
            totalQuantity = totalQuantity.add(quantity);
            originalAmount = originalAmount.add(originalLineAmount);
            linePayableAmount = linePayableAmount.add(originalLineAmount.subtract(lineDiscountAmount));
        }
        BigDecimal orderDiscountAmount = amount(discountAmount);
        if (orderDiscountAmount.compareTo(BigDecimal.ZERO) == 0 && discountRate != null) {
            orderDiscountAmount = linePayableAmount.multiply(discountRate);
        }
        return new SalesOrderHeaderAmounts(totalQuantity, originalAmount,
                orderDiscountAmount, linePayableAmount.subtract(orderDiscountAmount));
    }

    private static boolean sameSalesOrderProjection(SalesOrderCommand expected, SalesOrderDetailView actual) {
        if (expected == null || actual == null) return false;
        return java.util.Objects.equals(expected.customerId(), actual.customerId())
                && java.util.Objects.equals(expected.sourceSystemCode(), actual.sourceSystemCode())
                && java.util.Objects.equals(expected.sourceOrderNo(), actual.sourceOrderNo())
                && java.util.Objects.equals(expected.sourceStatusCode(), actual.sourceStatusCode())
                && java.util.Objects.equals(expected.sourceCreatorId(), actual.sourceCreatorId())
                && java.util.Objects.equals(expected.sourceCreatorStaffCode(), actual.sourceCreatorStaffCode())
                && java.util.Objects.equals(expected.sourceCreatorName(), actual.sourceCreatorName())
                && java.util.Objects.equals(expected.customerCodeSnapshot(), actual.customerCodeSnapshot())
                && java.util.Objects.equals(expected.customerNameSnapshot(), actual.customerNameSnapshot())
                && java.util.Objects.equals(expected.contactNameSnapshot(), actual.contactNameSnapshot())
                && java.util.Objects.equals(expected.contactPhoneSnapshot(), actual.contactPhoneSnapshot())
                && java.util.Objects.equals(expected.regionCode(), actual.regionCode())
                && java.util.Objects.equals(expected.ownerSalesUserId(), actual.ownerSalesUserId())
                && java.util.Objects.equals(expected.ownerSalesName(), actual.ownerSalesName())
                && java.util.Objects.equals(expected.ownerEmployeeCode(), actual.ownerEmployeeCode())
                && java.util.Objects.equals(expected.ownerEmployeeNameSnapshot(), actual.ownerEmployeeNameSnapshot())
                && java.util.Objects.equals(expected.orderDate(), actual.orderDate())
                && java.util.Objects.equals(expected.orderTypeCode(), actual.orderTypeCode())
                && java.util.Objects.equals(expected.paymentMethodCode(), actual.paymentMethodCode())
                && java.util.Objects.equals(
                        expected.paymentVoucherKeys() == null ? List.of() : expected.paymentVoucherKeys(),
                        actual.paymentVoucherKeys() == null ? List.of() : actual.paymentVoucherKeys())
                && compare(expected.discountRate(), actual.discountRate()) == 0
                && compare(expected.discountAmount(), actual.discountAmount()) == 0
                && (expected.sourceUnpaidAmount() == null
                || compare(expected.sourceUnpaidAmount(), actual.unpaidAmount()) == 0)
                && java.util.Objects.equals(expected.remark(), actual.remark())
                && sameSalesOrderLines(expected.lines(), actual.lines());
    }

    private static int compare(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right ? 0 : -1;
        return left.compareTo(right);
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private SalesOrderWriteResult writeSalesOrder(CallerIdentity caller, SalesOrderCommand command) {
        CallerIdentity serviceCaller = orderProjectionCaller(caller.tenantId());
        try {
            Optional<SalesOrderDetailView> existing = orderSalesOrderProjectionClient.findSalesOrderBySource(
                    serviceCaller, SOURCE_SYSTEM, command.sourceOrderNo());
            SalesOrderDetailView detail;
            String message;
            if (existing.isPresent()) {
                SalesOrderDetailView current = existing.get();
                if (sameSalesOrderProjection(command, current)) {
                    return new SalesOrderWriteResult(new ProjectionDecision("SKIPPED", "ORDER", "SALES_ORDER",
                            String.valueOf(current.id()), null, "飞书销售订单已存在且无变化，跳过重复写入"), current);
                }
                detail = orderSalesOrderProjectionClient.updateSalesOrder(serviceCaller, current.id(),
                        commandWithRevision(command, current.revision()));
                message = "飞书销售订单已存在，已按本次导入内容更新";
            } else {
                detail = orderSalesOrderProjectionClient.createSalesOrder(serviceCaller, command);
                message = "飞书销售订单已导入Order";
            }
            return new SalesOrderWriteResult(new ProjectionDecision("PROJECTED", "ORDER", "SALES_ORDER",
                    String.valueOf(detail.id()), null, message), detail);
        } catch (RuntimeException exception) {
            return new SalesOrderWriteResult(new ProjectionDecision("FAILED", "ORDER", "SALES_ORDER", null,
                    "FEISHU_ORDER_PROJECT_FAILED", clean(exception.getMessage())), null);
        }
    }

    private ProjectionDecision projectSalesOrder(CallerIdentity caller, StoredRawRow row, boolean dryRun,
                                                 FeishuSalesOrderImportMapper.MappingContext mappingContext) {
        FeishuSalesOrderImportMapper.ProjectionPlan plan = salesOrderImportMapper.plan(row, mappingContext);
        if (plan.command() == null) {
            return new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                    null, plan.errorCode(), plan.message());
        }
        SalesOrderCommand command = plan.command();
        if (dryRun) {
            return new ProjectionDecision("SKIPPED", "ORDER", "SALES_ORDER",
                    null, null, "试跑通过，正式写入时会导入Order销售订单");
        }
        if (orderSalesOrderProjectionClient == null) {
            return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                    null, "FEISHU_ORDER_PROJECTION_CLIENT_REQUIRED", "Order投影客户端未装配");
        }
        return writeSalesOrder(caller, command).decision();
    }

    private ProjectionDecision projectSalesOrderLine(CallerIdentity caller, StoredRawRow row, boolean dryRun,
                                                     FeishuSalesOrderImportMapper.MappingContext mappingContext) {
        FeishuSalesOrderImportMapper.LineProjectionPlan plan = salesOrderImportMapper.linePlan(row, mappingContext);
        if (plan.command() == null) {
            return new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                    null, plan.errorCode(), plan.message());
        }
        return new ProjectionDecision("WAITING_MAPPING", "ORDER", "SALES_ORDER_LINE",
                null, "FEISHU_ORDER_HEADER_REQUIRED",
                dryRun ? "订单明细试跑通过，但需要同批次销售订单主表一起正式导入"
                        : "订单明细已识别，但需要同批次销售订单主表一起正式导入");
    }

    private Map<UUID, ProjectionDecision> projectSalesPaymentsFromOrders(
            CallerIdentity caller,
            List<StoredRawRow> salesOrderRows,
            boolean dryRun,
            Map<String, SalesOrderDetailView> ordersBySourceNo,
            Map<UUID, ProjectionDecision> currentDecisions,
            FeishuSalesOrderImportMapper.MappingContext mappingContext,
            ProjectionProgressRecorder progressRecorder) {
        Map<UUID, ProjectionDecision> decisions = new HashMap<>();
        if (salesOrderRows.isEmpty()) return decisions;
        for (StoredRawRow row : salesOrderRows) {
            ProjectionDecision orderDecision = currentDecisions.get(row.id());
            if (!salesOrderAvailableForPayment(orderDecision, dryRun)) {
                continue;
            }
            ProjectionDecision paymentDecision = projectSalesPayment(
                    caller, row, dryRun, ordersBySourceNo, mappingContext);
            if (paymentDecision == null) continue;
            putProjectionDecision(decisions, row,
                    combineSalesOrderPaymentDecision(orderDecision, paymentDecision),
                    progressRecorder);
        }
        return decisions;
    }

    private Map<UUID, ProjectionDecision> projectSalesPayments(
            CallerIdentity caller,
            List<StoredRawRow> salesPaymentRows,
            boolean dryRun,
            Map<String, SalesOrderDetailView> ordersBySourceNo,
            FeishuSalesOrderImportMapper.MappingContext mappingContext,
            ProjectionProgressRecorder progressRecorder) {
        Map<UUID, ProjectionDecision> decisions = new HashMap<>();
        if (salesPaymentRows.isEmpty()) return decisions;
        for (StoredRawRow row : salesPaymentRows) {
            putProjectionDecision(decisions, row,
                    projectSalesPayment(caller, row, dryRun, ordersBySourceNo, mappingContext),
                    progressRecorder);
        }
        return decisions;
    }

    private ProjectionDecision projectSalesPayment(CallerIdentity caller, StoredRawRow row, boolean dryRun,
                                                   Map<String, SalesOrderDetailView> ordersBySourceNo,
                                                   FeishuSalesOrderImportMapper.MappingContext mappingContext) {
        String sourceOrderNo = salesPaymentSourceOrderNo(row);
        SalesOrderDetailView order = sourceOrderNo == null || ordersBySourceNo == null
                ? null
                : ordersBySourceNo.get(sourceOrderNo);
        if (!dryRun && order == null && sourceOrderNo != null) {
            if (orderSalesOrderProjectionClient == null) {
                return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                        null, "FEISHU_ORDER_PROJECTION_CLIENT_REQUIRED", "Order投影客户端未装配，不能查找回款所属销售订单");
            }
            try {
                order = orderSalesOrderProjectionClient.findSalesOrderBySource(
                                orderProjectionCaller(caller.tenantId()), SOURCE_SYSTEM, sourceOrderNo)
                        .orElse(null);
            } catch (RuntimeException exception) {
                return new ProjectionDecision("FAILED", "ORDER", "PAYMENT_RECORD",
                        null, "FEISHU_PAYMENT_ORDER_LOOKUP_FAILED", clean(exception.getMessage()));
            }
        }
        FeishuSalesPaymentImportMapper.PaymentProjectionPlan plan = isSalesOrderProjection(row)
                ? salesPaymentImportMapper.planFromSalesOrder(row, order, mappingContext)
                : salesPaymentImportMapper.plan(row, order, mappingContext);
        if (plan.command() == null) {
            if ("SKIPPED".equals(plan.status())) return null;
            return new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                    null, plan.errorCode(), plan.message());
        }
        if (dryRun) {
            return new ProjectionDecision("SKIPPED", "ORDER", "PAYMENT_RECORD",
                    null, null, "试跑通过，正式写入时会生成Order销售回款记录");
        }
        if (orderSalesOrderProjectionClient == null) {
            return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                    null, "FEISHU_ORDER_PROJECTION_CLIENT_REQUIRED", "Order投影客户端未装配，不能写入销售回款记录");
        }
        return writeSalesPayment(caller, plan.command());
    }

    private ProjectionDecision writeSalesPayment(CallerIdentity caller, SalesPaymentRecordCommand command) {
        CallerIdentity serviceCaller = orderProjectionCaller(caller.tenantId());
        try {
            Optional<SalesPaymentRecordDetailView> existing =
                    orderSalesOrderProjectionClient.findSalesPaymentBySource(
                            serviceCaller, SOURCE_SYSTEM, command.sourceDocumentNo());
            SalesPaymentRecordDetailView detail;
            String message;
            if (existing.isPresent()) {
                SalesPaymentRecordDetailView current = existing.get();
                if (FeishuSalesPaymentImportMapper.samePayment(command, current)) {
                    return new ProjectionDecision("SKIPPED", "ORDER", "PAYMENT_RECORD",
                            String.valueOf(current.id()), null, "飞书回款记录已存在且无变化，跳过重复写入");
                }
                detail = orderSalesOrderProjectionClient.updateSalesPayment(
                        serviceCaller, current.id(),
                        FeishuSalesPaymentImportMapper.commandWithRevision(command, current.revision()));
                message = "飞书回款记录已存在，已按本次导入内容更新";
            } else {
                detail = orderSalesOrderProjectionClient.createSalesPayment(serviceCaller, command);
                message = "飞书回款记录已导入Order";
            }
            return new ProjectionDecision("PROJECTED", "ORDER", "PAYMENT_RECORD",
                    String.valueOf(detail.id()), null, message);
        } catch (RuntimeException exception) {
            return new ProjectionDecision("FAILED", "ORDER", "PAYMENT_RECORD", null,
                    "FEISHU_PAYMENT_PROJECT_FAILED", clean(exception.getMessage()));
        }
    }

    private static String salesPaymentSourceOrderNo(StoredRawRow row) {
        if (row == null) return null;
        String value = FeishuSalesOrderImportMapper.sourceDocumentNo(row.values());
        if (value != null) return value;
        return isSalesOrderProjection(row) ? clean(row.sourceDocumentNo()) : null;
    }

    private static ProjectionDecision combineSalesOrderPaymentDecision(
            ProjectionDecision orderDecision, ProjectionDecision paymentDecision) {
        if (paymentDecision == null) return orderDecision;
        if ("PROJECTED".equals(paymentDecision.status()) && !"PROJECTED".equals(orderDecision.status())) {
            return new ProjectionDecision("PROJECTED", "ORDER", "PAYMENT_RECORD",
                    paymentDecision.targetId(), paymentDecision.errorCode(),
                    appendMessage(orderDecision.message(), paymentDecision.message()),
                    orderDecision.customerCode(), orderDecision.productVariantId(), orderDecision.productCode(),
                    orderDecision.variantCode(), orderDecision.unitCode());
        }
        if ("PROJECTED".equals(paymentDecision.status()) || "SKIPPED".equals(paymentDecision.status())) {
            return new ProjectionDecision(orderDecision.status(), orderDecision.targetDomain(),
                    orderDecision.targetObjectType(), orderDecision.targetId(),
                    orderDecision.errorCode(), appendMessage(orderDecision.message(), paymentDecision.message()),
                    orderDecision.customerCode(), orderDecision.productVariantId(), orderDecision.productCode(),
                    orderDecision.variantCode(), orderDecision.unitCode());
        }
        return new ProjectionDecision(paymentDecision.status(), "ORDER", "PAYMENT_RECORD",
                paymentDecision.targetId(), paymentDecision.errorCode(),
                appendMessage("销售订单已导入，但回款生成未完成", paymentDecision.message()));
    }

    private static boolean salesOrderAvailableForPayment(ProjectionDecision orderDecision, boolean dryRun) {
        if (orderDecision == null) return false;
        if ("PROJECTED".equals(orderDecision.status())) return true;
        return !dryRun && "SKIPPED".equals(orderDecision.status());
    }

    private static ProjectionDecision withAttachmentIssue(
            ProjectionDecision current, ProjectionDecision attachmentDecision) {
        if (current == null) return attachmentDecision;
        boolean businessProjected = "PROJECTED".equals(current.status()) || "SKIPPED".equals(current.status());
        String status = businessProjected ? "WAITING_MAPPING" : current.status();
        String messagePrefix = businessProjected ? "业务主体已写入，但附件未入库：" : "附件未入库：";
        String errorCode = businessProjected
                ? firstNonBlank(attachmentDecision.errorCode(), current.errorCode())
                : firstNonBlank(current.errorCode(), attachmentDecision.errorCode());
        return new ProjectionDecision(status, current.targetDomain(), current.targetObjectType(),
                current.targetId(), errorCode,
                appendMessage(current.message(), messagePrefix + attachmentDecision.message()),
                current.customerCode(), current.productVariantId(), current.productCode(),
                current.variantCode(), current.unitCode());
    }

    private static String appendMessage(String left, String right) {
        String a = clean(left);
        String b = clean(right);
        if (a == null) return b;
        if (b == null) return a;
        return a + "；" + b;
    }

    private ProjectionDecision projectEmployee(CallerIdentity caller, StoredRawRow row, boolean dryRun) {
        FeishuStaffImportMapper.ProjectionPlan plan = staffImportMapper.plan(row);
        if (plan.command() == null) {
            return new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                    null, plan.errorCode(), plan.message());
        }
        ExternalEmployeeRowCommand command = plan.command();
        if (dryRun) {
            return new ProjectionDecision("SKIPPED", "HR", "EMPLOYEE",
                    null, null, "试跑通过，正式写入时会导入HR员工");
        }
        if (hrEmployeeProjectionClient == null) {
            return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                    null, "FEISHU_HR_EMPLOYEE_CLIENT_REQUIRED", "HR员工投影客户端未装配");
        }
        try {
            ExternalEmployeeSyncResult result = hrEmployeeProjectionClient.sync(
                    hrProjectionCaller(caller.tenantId()), SOURCE_SYSTEM, List.of(command));
            if (result.failed() > 0) {
                String message = result.failureMessages().isEmpty()
                        ? "HR员工导入失败"
                        : String.join("；", result.failureMessages());
                return new ProjectionDecision("FAILED", "HR", "EMPLOYEE", null,
                        "FEISHU_HR_EMPLOYEE_PROJECT_FAILED", message);
            }
            String employeeCode = result.rows().stream()
                    .filter(item -> command.sourceEmployeeId().equals(item.sourceEmployeeId()))
                    .map(ExternalEmployeeSyncRowResult::employeeCode)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(command.sourceEmployeeId());
            String message = result.created() > 0 ? "飞书人员已导入HR员工主档"
                    : result.updated() > 0 ? "飞书人员已更新HR员工主档"
                    : "飞书人员已存在，跳过重复写入";
            return new ProjectionDecision("PROJECTED", "HR", "EMPLOYEE",
                    employeeCode, null, message);
        } catch (RuntimeException exception) {
            return new ProjectionDecision("FAILED", "HR", "EMPLOYEE", null,
                    "FEISHU_HR_EMPLOYEE_PROJECT_FAILED", clean(exception.getMessage()));
        }
    }

    private Map<UUID, ProjectionDecision> projectEmployees(CallerIdentity caller, List<StoredRawRow> rows,
                                                           boolean dryRun) {
        Map<UUID, ProjectionDecision> decisions = new HashMap<>();
        if (rows.isEmpty()) return decisions;
        List<CommandRow<ExternalEmployeeRowCommand>> commandRows = new ArrayList<>();
        for (StoredRawRow row : rows) {
            FeishuStaffImportMapper.ProjectionPlan plan = staffImportMapper.plan(row);
            if (plan.command() == null) {
                decisions.put(row.id(), new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                        null, plan.errorCode(), plan.message()));
            } else if (dryRun) {
                decisions.put(row.id(), new ProjectionDecision("SKIPPED", "HR", "EMPLOYEE",
                        null, null, "试跑通过，正式写入时会导入HR员工"));
            } else {
                commandRows.add(new CommandRow<>(row, plan.command()));
            }
        }
        if (dryRun || commandRows.isEmpty()) return decisions;
        if (hrEmployeeProjectionClient == null) {
            commandRows.forEach(item -> decisions.put(item.row().id(),
                    new ProjectionDecision("WAITING_MAPPING", item.row().domainCode(), item.row().objectType(),
                            null, "FEISHU_HR_EMPLOYEE_CLIENT_REQUIRED", "HR员工投影客户端未装配")));
            return decisions;
        }
        CallerIdentity serviceCaller = hrProjectionCaller(caller.tenantId());
        for (int start = 0; start < commandRows.size(); start += DOMAIN_PROJECTION_BATCH_SIZE) {
            List<CommandRow<ExternalEmployeeRowCommand>> chunk = commandRows.subList(start,
                    Math.min(start + DOMAIN_PROJECTION_BATCH_SIZE, commandRows.size()));
            try {
                ExternalEmployeeSyncResult result = hrEmployeeProjectionClient.sync(serviceCaller, SOURCE_SYSTEM,
                        chunk.stream().map(CommandRow::command).toList());
                Map<String, ExternalEmployeeSyncRowResult> resultBySource = new HashMap<>();
                result.rows().forEach(item -> resultBySource.putIfAbsent(item.sourceEmployeeId(), item));
                for (CommandRow<ExternalEmployeeRowCommand> item : chunk) {
                    ExternalEmployeeSyncRowResult rowResult = resultBySource.get(item.command().sourceEmployeeId());
                    if (rowResult == null) {
                        decisions.put(item.row().id(), new ProjectionDecision("FAILED", "HR", "EMPLOYEE",
                                null, "FEISHU_HR_EMPLOYEE_PROJECT_FAILED", "HR员工同步未返回行结果"));
                    } else if ("FAILED".equals(rowResult.status())) {
                        decisions.put(item.row().id(), new ProjectionDecision("FAILED", "HR", "EMPLOYEE",
                                null, "FEISHU_HR_EMPLOYEE_PROJECT_FAILED", firstNonBlank(rowResult.message(),
                                failureMessage(result.failureMessages(), "HR员工导入失败"))));
                    } else {
                        String employeeCode = firstNonBlank(rowResult.employeeCode(), item.command().sourceEmployeeId());
                        decisions.put(item.row().id(), new ProjectionDecision("PROJECTED", "HR", "EMPLOYEE",
                                employeeCode, null, firstNonBlank(rowResult.message(), "飞书人员已导入HR员工主档")));
                    }
                }
            } catch (RuntimeException exception) {
                String message = clean(exception.getMessage());
                chunk.forEach(item -> decisions.put(item.row().id(),
                        new ProjectionDecision("FAILED", "HR", "EMPLOYEE", null,
                                "FEISHU_HR_EMPLOYEE_PROJECT_FAILED", message)));
            }
        }
        return decisions;
    }

    private List<ImportTemplate> importTemplates(UUID tenantId) {
        LinkedHashMap<String, ImportTemplate> templates = new LinkedHashMap<>();
        for (ImportTemplate template : FeishuImportTableCatalog.defaultTemplates()) {
            templates.put(template.templateCode(), template);
        }
        for (ImportTemplate template : store.importTemplates(tenantId)) {
            if (template == null || template.templateCode() == null || template.templateCode().isBlank()) continue;
            templates.put(template.templateCode(), template);
        }
        return List.copyOf(templates.values());
    }

    private static FeishuImportTemplateView templateView(ImportTemplate template) {
        return new FeishuImportTemplateView(template.templateCode(), template.templateName(),
                template.sourceSystem(), template.domainCode(), template.objectType(),
                template.aliases(), template.requiredHeaders(), template.sourceDocumentFields(),
                template.sourceCreatedFields(), template.deduplicationStrategy(),
                template.deduplicationFields(), template.readyByDefault(),
                template.dependencies().stream()
                        .map(dependency -> new FeishuImportTemplateDependencyView(
                                dependency.dependsOnTemplateCode(), dependency.relationKind(),
                                dependency.sourceReferenceFields(), dependency.targetReferenceFields(),
                                dependency.required()))
                        .toList());
    }

    private ProjectionDecision projectCrmCustomer(CallerIdentity caller, StoredRawRow row, boolean dryRun) {
        FeishuCrmCustomerImportMapper.ProjectionPlan plan = crmCustomerImportMapper.plan(row);
        if (plan.command() == null) {
            return new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                    null, plan.errorCode(), plan.message());
        }
        ExternalCrmCustomerRowCommand command = plan.command();
        if (dryRun) {
            return new ProjectionDecision("SKIPPED", "CRM", "CUSTOMER",
                    null, null, "试跑通过，正式写入时会导入CRM客户/门店");
        }
        if (crmCustomerProjectionClient == null) {
            return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                    null, "FEISHU_CRM_CUSTOMER_CLIENT_REQUIRED", "CRM客户投影客户端未装配");
        }
        try {
            ExternalCrmCustomerSyncResult result = crmCustomerProjectionClient.sync(
                    crmProjectionCaller(caller.tenantId()), SOURCE_SYSTEM, List.of(command));
            if (result.failed() > 0) {
                String message = result.failureMessages().isEmpty()
                        ? "CRM客户导入失败"
                        : String.join("；", result.failureMessages());
                return new ProjectionDecision("FAILED", "CRM", "CUSTOMER", null,
                        "FEISHU_CRM_CUSTOMER_PROJECT_FAILED", message);
            }
            ExternalCrmCustomerSyncRowResult rowResult = result.rows().stream()
                    .filter(item -> command.sourceCustomerId().equals(item.sourceCustomerId()))
                    .findFirst()
                    .orElse(null);
            String targetId = rowResult == null || rowResult.customerId() == null
                    ? command.sourceCustomerId()
                    : String.valueOf(rowResult.customerId());
            String message = result.created() > 0 ? "飞书客户/门店已导入CRM"
                    : result.updated() > 0 ? "飞书客户/门店已更新CRM"
                    : "飞书客户/门店已存在，跳过重复写入";
            return ProjectionDecision.customer(targetId, rowResult, message);
        } catch (RuntimeException exception) {
            return new ProjectionDecision("FAILED", "CRM", "CUSTOMER", null,
                    "FEISHU_CRM_CUSTOMER_PROJECT_FAILED", clean(exception.getMessage()));
        }
    }

    private Map<UUID, ProjectionDecision> projectCrmCustomers(CallerIdentity caller, List<StoredRawRow> rows,
                                                              boolean dryRun) {
        Map<UUID, ProjectionDecision> decisions = new HashMap<>();
        if (rows.isEmpty()) return decisions;
        List<CommandRow<ExternalCrmCustomerRowCommand>> commandRows = new ArrayList<>();
        for (StoredRawRow row : rows) {
            FeishuCrmCustomerImportMapper.ProjectionPlan plan = crmCustomerImportMapper.plan(row);
            if (plan.command() == null) {
                decisions.put(row.id(), new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                        null, plan.errorCode(), plan.message()));
            } else if (dryRun) {
                decisions.put(row.id(), new ProjectionDecision("SKIPPED", "CRM", "CUSTOMER",
                        null, null, "试跑通过，正式写入时会导入CRM客户/门店"));
            } else {
                commandRows.add(new CommandRow<>(row, plan.command()));
            }
        }
        if (dryRun || commandRows.isEmpty()) return decisions;
        if (crmCustomerProjectionClient == null) {
            commandRows.forEach(item -> decisions.put(item.row().id(),
                    new ProjectionDecision("WAITING_MAPPING", item.row().domainCode(), item.row().objectType(),
                            null, "FEISHU_CRM_CUSTOMER_CLIENT_REQUIRED", "CRM客户投影客户端未装配")));
            return decisions;
        }
        CallerIdentity serviceCaller = crmProjectionCaller(caller.tenantId());
        for (int start = 0; start < commandRows.size(); start += DOMAIN_PROJECTION_BATCH_SIZE) {
            List<CommandRow<ExternalCrmCustomerRowCommand>> chunk = commandRows.subList(start,
                    Math.min(start + DOMAIN_PROJECTION_BATCH_SIZE, commandRows.size()));
            try {
                ExternalCrmCustomerSyncResult result = crmCustomerProjectionClient.sync(serviceCaller, SOURCE_SYSTEM,
                        chunk.stream().map(CommandRow::command).toList());
                Map<String, ExternalCrmCustomerSyncRowResult> resultBySource = new HashMap<>();
                result.rows().forEach(item -> resultBySource.putIfAbsent(item.sourceCustomerId(), item));
                for (CommandRow<ExternalCrmCustomerRowCommand> item : chunk) {
                    ExternalCrmCustomerSyncRowResult rowResult = resultBySource.get(item.command().sourceCustomerId());
                    if (rowResult == null) {
                        decisions.put(item.row().id(), new ProjectionDecision("FAILED", "CRM", "CUSTOMER",
                                null, "FEISHU_CRM_CUSTOMER_PROJECT_FAILED", "CRM客户同步未返回行结果"));
                    } else if ("FAILED".equals(rowResult.status())) {
                        decisions.put(item.row().id(), new ProjectionDecision("FAILED", "CRM", "CUSTOMER",
                                null, "FEISHU_CRM_CUSTOMER_PROJECT_FAILED", firstNonBlank(rowResult.message(),
                                failureMessage(result.failureMessages(), "CRM客户导入失败"))));
                    } else {
                        String targetId = rowResult.customerId() == null
                                ? firstNonBlank(rowResult.customerCode(), item.command().sourceCustomerId())
                                : String.valueOf(rowResult.customerId());
                        decisions.put(item.row().id(), ProjectionDecision.customer(targetId, rowResult,
                                firstNonBlank(rowResult.message(), "飞书客户/门店已导入CRM")));
                    }
                }
            } catch (RuntimeException exception) {
                String message = clean(exception.getMessage());
                chunk.forEach(item -> decisions.put(item.row().id(),
                        new ProjectionDecision("FAILED", "CRM", "CUSTOMER", null,
                                "FEISHU_CRM_CUSTOMER_PROJECT_FAILED", message)));
            }
        }
        return decisions;
    }

    private ProjectionDecision projectCrmArea(CallerIdentity caller, StoredRawRow row, boolean dryRun) {
        if (row.sourceCreatedAt() == null) {
            return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                    null, "FEISHU_SOURCE_CREATED_AT_REQUIRED",
                    "缺少飞书创建时间，不能按源时间生成区域/城市编码");
        }
        List<ExternalCrmAreaRowCommand> commands = crmAreaImportMapper.rows(List.of(row));
        if (commands.isEmpty()) {
            return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                    null, "FEISHU_CRM_AREA_MAPPING_REQUIRED", "缺少区域或城市字段");
        }
        if (dryRun) {
            return new ProjectionDecision("SKIPPED", "CRM", "CUSTOMER_AREA",
                    null, null, "试跑通过，正式写入时会导入CRM区域/城市");
        }
        if (crmCustomerProjectionClient == null) {
            return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                    null, "FEISHU_CRM_AREA_CLIENT_REQUIRED", "CRM地区投影客户端未装配");
        }
        try {
            ExternalCrmAreaSyncResult result = crmCustomerProjectionClient.syncAreas(
                    crmProjectionCaller(caller.tenantId()), SOURCE_SYSTEM, commands);
            if (result.failed() > 0) {
                String message = result.failureMessages().isEmpty()
                        ? "CRM区域/城市导入失败"
                        : String.join("；", result.failureMessages());
                return new ProjectionDecision("FAILED", "CRM", "CUSTOMER_AREA", null,
                        "FEISHU_CRM_AREA_PROJECT_FAILED", message);
            }
            String targetId = result.rows().stream()
                    .map(item -> firstNonBlank(item.cityCode(), item.regionCode()))
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(commands.get(0).sourceAreaId());
            String message = result.created() > 0 ? "飞书区域/城市已导入CRM"
                    : result.updated() > 0 ? "飞书区域/城市已恢复启用"
                    : "飞书区域/城市已存在，跳过重复写入";
            return new ProjectionDecision("PROJECTED", "CRM", "CUSTOMER_AREA",
                    targetId, null, message);
        } catch (RuntimeException exception) {
            return new ProjectionDecision("FAILED", "CRM", "CUSTOMER_AREA", null,
                    "FEISHU_CRM_AREA_PROJECT_FAILED", clean(exception.getMessage()));
        }
    }

    private Map<UUID, ProjectionDecision> projectCrmAreas(CallerIdentity caller, List<StoredRawRow> rows,
                                                          boolean dryRun) {
        Map<UUID, ProjectionDecision> decisions = new HashMap<>();
        if (rows.isEmpty()) return decisions;
        List<CommandRow<ExternalCrmAreaRowCommand>> commandRows = new ArrayList<>();
        for (StoredRawRow row : rows) {
            if (row.sourceCreatedAt() == null) {
                decisions.put(row.id(), new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                        null, "FEISHU_SOURCE_CREATED_AT_REQUIRED",
                        "缺少飞书创建时间，不能按源时间生成区域/城市编码"));
                continue;
            }
            List<ExternalCrmAreaRowCommand> commands = crmAreaImportMapper.rows(List.of(row));
            if (commands.isEmpty()) {
                decisions.put(row.id(), new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                        null, "FEISHU_CRM_AREA_MAPPING_REQUIRED", "缺少区域或城市字段"));
            } else if (dryRun) {
                decisions.put(row.id(), new ProjectionDecision("SKIPPED", "CRM", "CUSTOMER_AREA",
                        null, null, "试跑通过，正式写入时会导入CRM区域/城市"));
            } else {
                commands.forEach(command -> commandRows.add(new CommandRow<>(row, command)));
            }
        }
        if (dryRun || commandRows.isEmpty()) return decisions;
        if (crmCustomerProjectionClient == null) {
            commandRows.forEach(item -> decisions.put(item.row().id(),
                    new ProjectionDecision("WAITING_MAPPING", item.row().domainCode(), item.row().objectType(),
                            null, "FEISHU_CRM_AREA_CLIENT_REQUIRED", "CRM地区投影客户端未装配")));
            return decisions;
        }
        CallerIdentity serviceCaller = crmProjectionCaller(caller.tenantId());
        for (int start = 0; start < commandRows.size(); start += DOMAIN_PROJECTION_BATCH_SIZE) {
            List<CommandRow<ExternalCrmAreaRowCommand>> chunk = commandRows.subList(start,
                    Math.min(start + DOMAIN_PROJECTION_BATCH_SIZE, commandRows.size()));
            try {
                ExternalCrmAreaSyncResult result = crmCustomerProjectionClient.syncAreas(serviceCaller, SOURCE_SYSTEM,
                        chunk.stream().map(CommandRow::command).toList());
                Map<String, ExternalCrmAreaSyncRowResult> resultBySource = new HashMap<>();
                result.rows().forEach(item -> resultBySource.putIfAbsent(item.sourceAreaId(), item));
                Set<UUID> completedRows = new java.util.HashSet<>();
                for (CommandRow<ExternalCrmAreaRowCommand> item : chunk) {
                    if (!completedRows.add(item.row().id())) continue;
                    ExternalCrmAreaSyncRowResult rowResult = resultBySource.get(item.command().sourceAreaId());
                    if (rowResult == null) {
                        decisions.put(item.row().id(), new ProjectionDecision("FAILED", "CRM", "CUSTOMER_AREA",
                                null, "FEISHU_CRM_AREA_PROJECT_FAILED", "CRM地区同步未返回行结果"));
                    } else if ("FAILED".equals(rowResult.status())) {
                        decisions.put(item.row().id(), new ProjectionDecision("FAILED", "CRM", "CUSTOMER_AREA",
                                null, "FEISHU_CRM_AREA_PROJECT_FAILED", firstNonBlank(rowResult.message(),
                                failureMessage(result.failureMessages(), "CRM地区导入失败"))));
                    } else {
                        String targetId = firstNonBlank(rowResult.cityCode(), rowResult.regionCode(),
                                item.command().sourceAreaId());
                        decisions.put(item.row().id(), new ProjectionDecision("PROJECTED", "CRM", "CUSTOMER_AREA",
                                targetId, null, firstNonBlank(rowResult.message(), "飞书区域/城市已导入CRM")));
                    }
                }
            } catch (RuntimeException exception) {
                String message = clean(exception.getMessage());
                chunk.forEach(item -> decisions.put(item.row().id(),
                        new ProjectionDecision("FAILED", "CRM", "CUSTOMER_AREA", null,
                                "FEISHU_CRM_AREA_PROJECT_FAILED", message)));
            }
        }
        return decisions;
    }

    private ProjectionDecision projectErpProduct(CallerIdentity caller, StoredRawRow row, boolean dryRun) {
        FeishuErpProductImportMapper.ProjectionPlan plan = erpProductImportMapper.plan(row);
        if (plan.command() == null) {
            return new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                    null, plan.errorCode(), plan.message());
        }
        ExternalProductRowCommand command = plan.command();
        if (dryRun) {
            return new ProjectionDecision("SKIPPED", "ERP", "PRODUCT",
                    null, null, "试跑通过，正式写入时会导入ERP商品");
        }
        if (erpProductProjectionClient == null) {
            return new ProjectionDecision("WAITING_MAPPING", row.domainCode(), row.objectType(),
                    null, "FEISHU_ERP_PRODUCT_CLIENT_REQUIRED", "ERP商品投影客户端未装配");
        }
        try {
            ExternalProductSyncResult result = erpProductProjectionClient.sync(
                    erpProjectionCaller(caller.tenantId()), SOURCE_SYSTEM, List.of(command));
            if (result.failed() > 0) {
                String message = result.failureMessages().isEmpty()
                        ? "ERP商品导入失败"
                        : String.join("；", result.failureMessages());
                return new ProjectionDecision("FAILED", "ERP", "PRODUCT", null,
                        "FEISHU_ERP_PRODUCT_PROJECT_FAILED", message);
            }
            ExternalProductSyncRowResult rowResult = result.rows().stream()
                    .filter(item -> command.sourceProductId().equals(item.sourceProductId()))
                    .findFirst()
                    .orElse(null);
            String targetId = rowResult == null || rowResult.productId() == null
                    ? command.sourceProductId()
                    : String.valueOf(rowResult.productId());
            String message = result.created() > 0 ? "飞书产品已导入ERP商品"
                    : result.updated() > 0 ? "飞书产品已更新ERP商品"
                    : "飞书产品已存在，跳过重复写入";
            return ProjectionDecision.product(targetId, rowResult, message);
        } catch (RuntimeException exception) {
            return new ProjectionDecision("FAILED", "ERP", "PRODUCT", null,
                    "FEISHU_ERP_PRODUCT_PROJECT_FAILED", clean(exception.getMessage()));
        }
    }

    private Map<UUID, ProjectionDecision> projectErpProducts(CallerIdentity caller, List<StoredRawRow> rows,
                                                             boolean dryRun) {
        Map<UUID, ProjectionDecision> decisions = new HashMap<>();
        if (rows.isEmpty()) return decisions;
        List<CommandRow<ExternalProductRowCommand>> commandRows = new ArrayList<>();
        for (StoredRawRow row : rows) {
            FeishuErpProductImportMapper.ProjectionPlan plan = erpProductImportMapper.plan(row);
            if (plan.command() == null) {
                decisions.put(row.id(), new ProjectionDecision(plan.status(), row.domainCode(), row.objectType(),
                        null, plan.errorCode(), plan.message()));
            } else if (dryRun) {
                decisions.put(row.id(), new ProjectionDecision("SKIPPED", "ERP", "PRODUCT",
                        null, null, "试跑通过，正式写入时会导入ERP商品"));
            } else {
                commandRows.add(new CommandRow<>(row, plan.command()));
            }
        }
        if (dryRun || commandRows.isEmpty()) return decisions;
        if (erpProductProjectionClient == null) {
            commandRows.forEach(item -> decisions.put(item.row().id(),
                    new ProjectionDecision("WAITING_MAPPING", item.row().domainCode(), item.row().objectType(),
                            null, "FEISHU_ERP_PRODUCT_CLIENT_REQUIRED", "ERP商品投影客户端未装配")));
            return decisions;
        }
        CallerIdentity serviceCaller = erpProjectionCaller(caller.tenantId());
        for (int start = 0; start < commandRows.size(); start += DOMAIN_PROJECTION_BATCH_SIZE) {
            List<CommandRow<ExternalProductRowCommand>> chunk = commandRows.subList(start,
                    Math.min(start + DOMAIN_PROJECTION_BATCH_SIZE, commandRows.size()));
            try {
                ExternalProductSyncResult result = erpProductProjectionClient.sync(serviceCaller, SOURCE_SYSTEM,
                        chunk.stream().map(CommandRow::command).toList());
                Map<String, ExternalProductSyncRowResult> resultBySource = new HashMap<>();
                result.rows().forEach(item -> resultBySource.putIfAbsent(item.sourceProductId(), item));
                for (CommandRow<ExternalProductRowCommand> item : chunk) {
                    ExternalProductSyncRowResult rowResult = resultBySource.get(item.command().sourceProductId());
                    if (rowResult == null) {
                        decisions.put(item.row().id(), new ProjectionDecision("FAILED", "ERP", "PRODUCT",
                                null, "FEISHU_ERP_PRODUCT_PROJECT_FAILED", "ERP商品同步未返回行结果"));
                    } else if ("FAILED".equals(rowResult.status())) {
                        decisions.put(item.row().id(), new ProjectionDecision("FAILED", "ERP", "PRODUCT",
                                null, "FEISHU_ERP_PRODUCT_PROJECT_FAILED", firstNonBlank(rowResult.message(),
                                failureMessage(result.failureMessages(), "ERP商品导入失败"))));
                    } else {
                        String targetId = rowResult.productId() == null
                                ? firstNonBlank(rowResult.productCode(), item.command().sourceProductId())
                                : String.valueOf(rowResult.productId());
                        decisions.put(item.row().id(), ProjectionDecision.product(targetId, rowResult,
                                firstNonBlank(rowResult.message(), "飞书产品已导入ERP商品")));
                    }
                }
            } catch (RuntimeException exception) {
                String message = clean(exception.getMessage());
                chunk.forEach(item -> decisions.put(item.row().id(),
                        new ProjectionDecision("FAILED", "ERP", "PRODUCT", null,
                                "FEISHU_ERP_PRODUCT_PROJECT_FAILED", message)));
            }
        }
        return decisions;
    }

    private void syncDictionaries(CallerIdentity caller, List<StoredRawRow> rows) {
        if (businessDictionaryBatchClient == null || rows == null || rows.isEmpty()) return;
        Audit audit = businessDictionaryBatchClient.sync(
                BusinessDictionaryBatchClient.serviceCaller(
                        "rigour-integration-feishu-import-service", "FEISHU_IMPORT_SERVICE", caller.tenantId()),
                "FEISHU_IMPORT", dictionaryObservationMapper.observations(rows));
        if (audit.unmapped() > 0) {
            // 字典客户端内部已经按具体字典和值记录告警；导入不能因为字典服务暂时不可用整体中断。
            log.warn("飞书导入字典同步存在未解析项 tenantId={} count={}", caller.tenantId(), audit.unmapped());
        }
    }

    private Map<String, String> syncCrmAreaMasters(CallerIdentity caller, List<StoredRawRow> rows) {
        if (crmCustomerProjectionClient == null || rows == null || rows.isEmpty()) return Map.of();
        List<ExternalCrmAreaRowCommand> areaRows = crmAreaImportMapper.rows(rows);
        if (areaRows.isEmpty()) return Map.of();
        try {
            ExternalCrmAreaSyncResult result = crmCustomerProjectionClient.syncAreas(
                    crmProjectionCaller(caller.tenantId()), SOURCE_SYSTEM, areaRows);
            if (result.failed() > 0) {
                String message = result.failureMessages().isEmpty()
                        ? "CRM地区同步失败"
                        : String.join("；", result.failureMessages());
                log.warn("飞书区域/城市预同步存在失败项 tenantId={} received={} failed={} reason={}",
                        caller.tenantId(), result.received(), result.failed(), clean(message));
                return Map.of();
            }
            log.info("飞书区域/城市预同步完成 tenantId={} received={} created={} updated={} unchanged={}",
                    caller.tenantId(), result.received(), result.created(), result.updated(), result.unchanged());
            return areaCodesByName(areaRows, result);
        } catch (BusinessException exception) {
            log.warn("飞书区域/城市预同步不可用 tenantId={} reason={}",
                    caller.tenantId(), clean(exception.getMessage()));
        } catch (RuntimeException exception) {
            log.warn("飞书区域/城市预同步不可用 tenantId={} errorType={} reason={}",
                    caller.tenantId(), exception.getClass().getSimpleName(), clean(exception.getMessage()));
        }
        return Map.of();
    }

    private static Map<String, String> areaCodesByName(
            List<ExternalCrmAreaRowCommand> commands, ExternalCrmAreaSyncResult result) {
        if (commands == null || commands.isEmpty() || result == null || result.rows() == null) return Map.of();
        Map<String, ExternalCrmAreaRowCommand> commandsBySourceAreaId = new HashMap<>();
        for (ExternalCrmAreaRowCommand command : commands) {
            if (command == null || clean(command.sourceAreaId()) == null) continue;
            commandsBySourceAreaId.putIfAbsent(clean(command.sourceAreaId()), command);
        }
        Map<String, String> areaCodes = new HashMap<>();
        for (ExternalCrmAreaSyncRowResult row : result.rows()) {
            if (row == null || "FAILED".equals(row.status())) continue;
            ExternalCrmAreaRowCommand command = commandsBySourceAreaId.get(clean(row.sourceAreaId()));
            if (command == null) continue;
            addAreaCode(areaCodes, command.cityName(), firstNonBlank(row.cityCode(), row.regionCode()));
            addAreaCode(areaCodes, command.regionName(), firstNonBlank(row.regionCode(), row.cityCode()));
        }
        return Map.copyOf(areaCodes);
    }

    private static void addAreaCode(Map<String, String> areaCodes, String name, String code) {
        String key = SalesOrderMappingContext.normalizeReference(name);
        String value = clean(code);
        if (key != null && value != null) areaCodes.putIfAbsent(key, value);
    }

    private static CallerIdentity hrProjectionCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("FEISHU_IMPORT_SERVICE"),
                HR_EMPLOYEE_PROJECTION_PERMISSIONS);
    }

    private static CallerIdentity crmProjectionCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("FEISHU_IMPORT_SERVICE"),
                CRM_CUSTOMER_PROJECTION_PERMISSIONS);
    }

    private static CallerIdentity erpProjectionCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("FEISHU_IMPORT_SERVICE"),
                ERP_PRODUCT_PROJECTION_PERMISSIONS);
    }

    private static CallerIdentity orderProjectionCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("FEISHU_IMPORT_SERVICE"),
                ORDER_SALES_PROJECTION_PERMISSIONS);
    }

    private static int runLimit(FeishuImportRunCommand command) {
        if (command == null || command.maxRows() == null) return DEFAULT_RUN_LIMIT;
        return Math.max(1, Math.min(command.maxRows(), MAX_RUN_LIMIT));
    }

    private static String runStatus(int projected, int skipped, int waiting, int failed,
                                    int total, boolean dryRun) {
        if (dryRun) return "DRY_RUN";
        if (total == 0) return "SUCCEEDED";
        if (failed == total) return "FAILED";
        if (waiting > 0 || failed > 0) return "PARTIAL";
        return projected + skipped == total ? "SUCCEEDED" : "PARTIAL";
    }

    private static int count(Map<String, Long> counts, String status) {
        if (counts == null || status == null) return 0;
        return Math.toIntExact(Math.min(counts.getOrDefault(status, 0L), Integer.MAX_VALUE));
    }

    private static String projectionStatusMessage(String status) {
        return switch (firstNonBlank(status, "PENDING")) {
            case "PROJECTED" -> "已写入对应业务表";
            case "SKIPPED" -> "重复且无变化，已跳过";
            case "FAILED" -> "导入失败，查看错误原因";
            case "WAITING_MAPPING" -> "等待字段、主数据或依赖关系补齐";
            default -> "等待后台导入处理";
        };
    }

    private String rowHash(Map<String, String> values) {
        try {
            String canonical = objectMapper.writeValueAsString(new TreeMap<>(values == null ? Map.of() : values));
            MessageDigest digest = sha256Digest();
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (RuntimeException exception) {
            MessageDigest digest = sha256Digest();
            digest.update(String.valueOf(values).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private String payloadJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(values == null ? Map.of() : values));
        } catch (RuntimeException exception) {
            return "{}";
        }
    }

    private DeduplicationResult applyDeduplication(
            List<PreflightRawRow> rows,
            Map<String, ExistingDeduplicationRow> existingRows) {
        if (rows.isEmpty()) return new DeduplicationResult(List.of(), 0L, List.of());
        Map<String, PreflightRawRow> acceptedRows = new HashMap<>();
        Map<String, Long> unchanged = new TreeMap<>();
        Map<String, Long> changed = new TreeMap<>();
        List<PreflightRawRow> evaluatedRows = new ArrayList<>(rows.size());
        long duplicates = 0L;
        for (PreflightRawRow row : rows) {
            String key = row.deduplicationKey();
            if (key == null || key.isBlank()) {
                evaluatedRows.add(row);
                continue;
            }
            DuplicateEvaluation evaluation = duplicateEvaluation(row, acceptedRows.get(key),
                    existingRows == null ? null : existingRows.get(key));
            if (evaluation.scope() != null) {
                duplicates++;
                String issueKey = row.sheetName() + "\u0001" + evaluation.issueType();
                if (evaluation.noChange()) {
                    unchanged.merge(issueKey, 1L, Long::sum);
                } else {
                    changed.merge(issueKey, 1L, Long::sum);
                }
                boolean dropDuplicate = evaluation.noChange() && evaluation.currentBatchDuplicate();
                row = new PreflightRawRow(row.id(), row.tableId(), row.sheetName(), row.tableCode(),
                        row.domainCode(), row.objectType(), row.rowNumber(), row.sourceDocumentNo(),
                        row.sourceCreatedAt(), row.rowHash(), row.values(), row.attachmentRefs(),
                        row.deduplicationKey(), evaluation.scope(), evaluation.duplicateOfRawRowId(),
                        dropDuplicate ? "DROPPED" : "IMPORTED",
                        dropDuplicate ? "SKIPPED" : "PENDING");
            }
            evaluatedRows.add(row);
            if (!"DROPPED".equals(row.importStatus())) {
                acceptedRows.put(key, row);
            }
        }
        List<FeishuImportIssueView> issues = new ArrayList<>();
        appendDuplicateIssues(issues, unchanged, "INFO", "FEISHU_DUPLICATE_UNCHANGED",
                "行与历史批次或当前文件内同业务数据完全一致；同批次重复会跳过，历史重复会重新校验业务库并按来源单号幂等写入");
        appendDuplicateIssues(issues, changed, "WARN", "FEISHU_DUPLICATE_CHANGED",
                "行存在相同业务去重键但内容发生变化，正式导入会继续处理并更新业务数据");
        return new DeduplicationResult(List.copyOf(evaluatedRows), duplicates, List.copyOf(issues));
    }

    private static DuplicateEvaluation duplicateEvaluation(PreflightRawRow current,
                                                           PreflightRawRow accepted,
                                                           ExistingDeduplicationRow existing) {
        if (accepted != null) {
            if (current.rowHash().equals(accepted.rowHash())) {
                return new DuplicateEvaluation("BATCH_UNCHANGED", accepted.id(), true,
                        "FEISHU_DUPLICATE_UNCHANGED");
            }
            return new DuplicateEvaluation("BATCH_CHANGED", accepted.id(), false,
                    "FEISHU_DUPLICATE_CHANGED");
        }
        if (existing != null) {
            if (!"PROJECTED".equals(existing.projectionStatus())) return DuplicateEvaluation.none();
            if (current.rowHash().equals(existing.rowHash())) {
                return new DuplicateEvaluation("HISTORY_UNCHANGED", existing.rawRowId(), true,
                        "FEISHU_DUPLICATE_UNCHANGED");
            }
            return new DuplicateEvaluation("HISTORY_CHANGED", existing.rawRowId(), false,
                    "FEISHU_DUPLICATE_CHANGED");
        }
        return DuplicateEvaluation.none();
    }

    private static void appendDuplicateIssues(List<FeishuImportIssueView> issues,
                                              Map<String, Long> grouped,
                                              String severity,
                                              String issueType,
                                              String suffix) {
        for (Map.Entry<String, Long> entry : grouped.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            String[] parts = entry.getKey().split("\u0001", 2);
            String tableName = parts.length == 0 ? null : parts[0];
            issues.add(issueView(severity, issueType, tableName,
                    null, "去重键", entry.getValue() + suffix));
        }
    }

    private static FeishuImportIssueView issueView(FeishuImportXlsxInspector.Issue issue) {
        return issueView(issue.severity(), issue.issueType(), issue.tableName(),
                issue.rowNumber(), issue.fieldName(), issue.message());
    }

    private static FeishuImportIssueView issueView(String severity, String issueType,
                                                   String tableName, Integer rowNumber,
                                                   String fieldName, String message) {
        IssueDisposition disposition = issueDisposition(severity, issueType);
        return new FeishuImportIssueView(severity, issueType, tableName, rowNumber,
                fieldName, message, disposition.category(), disposition.blocking(),
                disposition.action(), disposition.hint());
    }

    private static IssueDisposition issueDisposition(String severity, String issueType) {
        return switch (issueType == null ? "" : issueType) {
            case "FEISHU_ATTACHMENT_SOURCE_REQUIRED" -> new IssueDisposition(
                    "ATTACHMENT", false, "COMPENSATE_ATTACHMENT",
                    "当前文件包含附件引用；可先导入业务主体，附件保留待补偿。后续补充飞书Base/视图地址或附件包后，系统按工作表、来源单号、附件字段和文件名回填COS fileKey");
            case "FEISHU_FIELD_MAPPING_REQUIRED" -> new IssueDisposition(
                    "FIELD_MAPPING", true, "CONFIGURE_FIELD_MAPPING",
                    "先补齐字段映射和领域写入规则，再重跑该工作表的正式投影");
            case "FEISHU_TABLE_UNMAPPED" -> new IssueDisposition(
                    "TABLE_MAPPING", true, "CONFIGURE_TABLE_TEMPLATE",
                    "先配置工作表模板、目标业务域和字段映射，再执行正式导入");
            case "FEISHU_REQUIRED_FIELD_MISSING" -> new IssueDisposition(
                    "SOURCE_FILE", true, "FIX_REQUIRED_HEADER",
                    "源 Excel 缺少内部识别必需字段，需要补齐表头或配置替代字段");
            case "FEISHU_SHEET_ROW_LIMIT_EXCEEDED" -> new IssueDisposition(
                    "SOURCE_FILE", true, "SPLIT_SOURCE_FILE",
                    "当前工作表超过单表行数限制，需要拆分后重新预检");
            case "FEISHU_SOURCE_DOCUMENT_NO_MISSING" -> new IssueDisposition(
                    "DATA_QUALITY", true, "COMPLETE_SOURCE_DOCUMENT_NO",
                    "来源单号缺失会影响幂等和关联关系，需要补齐或配置替代来源字段");
            case "FEISHU_SOURCE_CREATED_AT_MISSING" -> new IssueDisposition(
                    "DATA_QUALITY", true, "COMPLETE_SOURCE_CREATED_AT",
                    "创建时间缺失会影响内部业务编码生成，需要补齐或配置替代时间字段");
            case "FEISHU_DUPLICATE_UNCHANGED" -> new IssueDisposition(
                    "DUPLICATE", false, "VERIFY_BUSINESS_IDEMPOTENCY",
                    "同批次重复会跳过；历史重复会重新校验业务库，业务已存在且无变化时才跳过");
            case "FEISHU_DUPLICATE_CHANGED" -> new IssueDisposition(
                    "DUPLICATE", false, "AUTO_UPDATE_CHANGED",
                    "重复但内容有变化，正式导入会按来源单号幂等更新业务数据");
            default -> new IssueDisposition(
                    "OTHER", "ERROR".equals(severity), "REVIEW",
                    "需要查看问题说明后决定是否补齐源数据或映射配置");
        };
    }

    private static String sourceDocumentNo(Map<String, String> values, FeishuImportTableCatalog.Match match) {
        List<String> fields = match != null && !match.sourceDocumentFields().isEmpty()
                ? match.sourceDocumentFields()
                : FeishuStaffImportMapper.TABLE_CODE.equals(match == null ? null : match.tableCode())
                ? STAFF_SOURCE_DOCUMENT_FIELDS
                : SOURCE_DOCUMENT_FIELDS;
        for (String field : fields) {
            String value = values == null ? null : values.get(field);
            if (value != null && !value.isBlank()) return clean(value);
        }
        return null;
    }

    private static Instant sourceCreatedAt(Map<String, String> values, FeishuImportTableCatalog.Match match) {
        if (values == null) return null;
        List<String> fields = match != null && !match.sourceCreatedFields().isEmpty()
                ? match.sourceCreatedFields()
                : FeishuStaffImportMapper.TABLE_CODE.equals(match == null ? null : match.tableCode())
                ? STAFF_SOURCE_CREATED_FIELDS
                : SOURCE_CREATED_FIELDS;
        for (String field : fields) {
            String value = values.get(field);
            Instant instant = parseInstant(value);
            if (instant != null) return instant;
        }
        return parseInstantFromEmbeddedDate(sourceDocumentNo(values, match));
    }

    private static String deduplicationKey(FeishuImportTableCatalog.Match match,
                                           Map<String, String> values,
                                           String sourceDocumentNo,
                                           String rowHash) {
        if (match == null || match.tableCode() == null || match.tableCode().isBlank()) return null;
        String strategy = firstNonBlank(match.deduplicationStrategy(), "SOURCE_DOCUMENT_NO");
        List<String> parts = new ArrayList<>();
        if ("FIELD_VALUES".equals(strategy) && !match.deduplicationFields().isEmpty()) {
            for (String field : match.deduplicationFields()) {
                String value = values == null ? null : values.get(field);
                if (value != null && !value.isBlank()) parts.add(field + "=" + clean(value));
            }
        } else if ("ROW_HASH".equals(strategy)) {
            parts.add("rowHash=" + rowHash);
        } else if (sourceDocumentNo != null) {
            parts.add("sourceDocumentNo=" + sourceDocumentNo);
        } else if (!match.deduplicationFields().isEmpty()) {
            for (String field : match.deduplicationFields()) {
                String value = values == null ? null : values.get(field);
                if (value != null && !value.isBlank()) parts.add(field + "=" + clean(value));
            }
        }
        if (parts.isEmpty()) {
            parts.add("rowHash=" + rowHash);
        }
        String canonical = SOURCE_SYSTEM + "|" + match.tableCode() + "|" + strategy + "|"
                + parts.stream()
                .map(FeishuImportBundleService::normalizeDeduplicationPart)
                .filter(part -> part != null && !part.isBlank())
                .reduce((left, right) -> left + "|" + right)
                .orElse(rowHash);
        if (canonical.length() <= 240) return canonical;
        MessageDigest digest = sha256Digest();
        digest.update(canonical.getBytes(StandardCharsets.UTF_8));
        return SOURCE_SYSTEM + "|" + match.tableCode() + "|HASH|"
                + HexFormat.of().formatHex(digest.digest());
    }

    private static String normalizeDeduplicationPart(String value) {
        String text = clean(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static SalesOrderCommand commandWithRevision(SalesOrderCommand command, Integer revision) {
        return new SalesOrderCommand(command.customerId(), command.sourceSystemCode(), command.sourceOrderNo(),
                command.sourceStatusCode(), command.sourceCreatorId(), command.sourceCreatorStaffCode(),
                command.sourceCreatorName(), command.customerCodeSnapshot(), command.customerNameSnapshot(),
                command.contactNameSnapshot(), command.contactPhoneSnapshot(), command.regionCode(),
                command.ownerSalesUserId(), command.ownerSalesName(), command.ownerEmployeeCode(),
                command.ownerEmployeeNameSnapshot(), command.orderDate(), command.orderTypeCode(),
                command.paymentMethodCode(), command.paymentVoucherKeys(), command.sourceUnpaidAmount(),
                command.discountRate(), command.discountAmount(),
                command.remark(), command.lines(), command.submit(), revision);
    }

    private static Instant parseInstant(String value) {
        String cleaned = clean(value);
        if (cleaned == null) return null;
        try {
            return Instant.parse(cleaned);
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地日期格式。
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(cleaned, formatter).atZone(SOURCE_TIME_ZONE).toInstant();
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一个格式。
            }
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(cleaned, formatter).atStartOfDay(SOURCE_TIME_ZONE).toInstant();
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一个格式。
            }
        }
        try {
            double serial = Double.parseDouble(cleaned);
            if (serial > 20_000 && serial < 80_000) {
                long days = (long) Math.floor(serial);
                long seconds = Math.round((serial - days) * 86_400D);
                return EXCEL_EPOCH.plusDays(days).atStartOfDay(SOURCE_TIME_ZONE).plusSeconds(seconds).toInstant();
            }
        } catch (NumberFormatException ignored) {
            // 非Excel序列号。
        }
        String leadingDate = leadingDate(cleaned);
        if (leadingDate != null && !leadingDate.equals(cleaned)) {
            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                try {
                    return LocalDate.parse(leadingDate, formatter).atStartOfDay(SOURCE_TIME_ZONE).toInstant();
                } catch (DateTimeParseException ignored) {
                    // 继续尝试下一个格式。
                }
            }
        }
        return null;
    }

    private static String leadingDate(String value) {
        Matcher matcher = LEADING_DATE_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Instant parseInstantFromEmbeddedDate(String value) {
        String cleaned = clean(value);
        if (cleaned == null) return null;
        Matcher matcher = EMBEDDED_YYYYMMDD_PATTERN.matcher(cleaned);
        if (!matcher.find()) return null;
        String date = matcher.group(1);
        try {
            return LocalDate.of(Integer.parseInt(date.substring(0, 4)),
                    Integer.parseInt(date.substring(4, 6)),
                    Integer.parseInt(date.substring(6, 8)))
                    .atStartOfDay(SOURCE_TIME_ZONE)
                    .toInstant();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private CopyResult copyToTemp(MultipartFile file, Path temporaryFile) throws IOException {
        MessageDigest digest = sha256Digest();
        long total = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(temporaryFile)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > properties.getMaxBytes()) {
                    throw new IllegalArgumentException("飞书导出文件大小超过限制");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (total == 0L) throw new IllegalArgumentException("请上传飞书导出的xlsx文件");
        return new CopyResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static String status(List<FeishuImportIssueView> issues) {
        boolean hasError = issues.stream().anyMatch(issue -> "ERROR".equals(issue.severity()));
        if (hasError) return "REJECTED";
        boolean hasWarning = issues.stream().anyMatch(issue -> "WARN".equals(issue.severity()));
        if (hasWarning) return "PREFLIGHTED_WITH_WARNINGS";
        return "PREFLIGHTED";
    }

    private static String safeFileName(String value) {
        String name = clean(value);
        if (name == null) return "feishu-export.xlsx";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static String batchFileName(List<String> fileNames) {
        List<String> names = fileNames == null ? List.of() : fileNames.stream()
                .map(FeishuImportBundleService::clean)
                .filter(name -> name != null && !name.isBlank())
                .toList();
        if (names.isEmpty()) return "feishu-export.xlsx";
        if (names.size() == 1) return names.get(0).length() > 255
                ? names.get(0).substring(0, 255)
                : names.get(0);
        String joined = String.join("、", names);
        if (joined.length() <= 255) return joined;
        String first = names.get(0);
        String suffix = " 等" + names.size() + "个文件";
        int maxFirst = Math.max(1, 255 - suffix.length());
        return first.length() > maxFirst ? first.substring(0, maxFirst) + suffix : first + suffix;
    }

    private static String value(Map<String, String> values, String... names) {
        if (values == null || names == null) return null;
        for (String name : names) {
            String value = values.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String sourceCodePrefix(String value) {
        String text = clean(value);
        if (text == null) return null;
        Matcher matcher = CODE_PREFIX_PATTERN.matcher(text);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String sourceCodeCandidate(String value) {
        String text = clean(value);
        if (text == null) return null;
        String prefix = sourceCodePrefix(text);
        if (prefix != null) return prefix;
        return text.matches("[A-Za-z]{1,16}\\d{2,}") ? text : null;
    }

    private static String nonCodeDescriptor(String value) {
        String text = clean(value);
        if (text == null || sourceCodeCandidate(text) != null) return null;
        return text;
    }

    private static String descriptorProductName(String value) {
        List<String> parts = descriptorParts(value);
        if (parts.size() == 2) return clean(parts.get(0));
        if (parts.size() < 3) return null;
        StringBuilder builder = new StringBuilder();
        for (int index = 1; index < parts.size() - 1; index++) {
            if (builder.length() > 0) builder.append('-');
            builder.append(parts.get(index));
        }
        return clean(builder.toString());
    }

    private static String descriptorSpecification(String value) {
        List<String> parts = descriptorParts(value);
        return parts.size() >= 2 ? clean(parts.get(parts.size() - 1)) : null;
    }

    private static List<String> descriptorParts(String value) {
        String text = clean(value);
        if (text == null || sourceCodeCandidate(text) != null) return List.of();
        List<String> parts = new ArrayList<>();
        for (String part : text.split("\\s*[-－–—]\\s*")) {
            String cleaned = clean(part);
            if (cleaned != null) parts.add(cleaned);
        }
        return parts;
    }

    private static String linkedDisplayName(String value) {
        String text = clean(value);
        if (text == null) return null;
        String prefix = sourceCodePrefix(text);
        if (prefix == null || text.length() <= prefix.length()) return null;
        String suffix = text.substring(prefix.length()).replaceFirst("^[-_：:\\s]+", "").strip();
        return suffix.isBlank() ? null : suffix;
    }

    private static boolean hasMultipleValues(String value) {
        if (value == null || value.isBlank()) return false;
        return value.split("[,，;；\\n\\r]+").length > 1;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static Instant firstNonNullInstant(Instant first, Instant second) {
        return first == null ? second : first;
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        String text = clean(value);
        if (text != null) values.put(key, text);
    }

    private static String failureMessage(List<String> messages, String fallback) {
        if (messages == null || messages.isEmpty()) return fallback;
        return String.join("；", messages);
    }

    private static String text(String value, int max) {
        String cleaned = clean(value);
        if (cleaned == null) return null;
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').strip();
        if (oneLine.isEmpty()) return null;
        return oneLine.length() > 2000 ? oneLine.substring(0, 2000) : oneLine;
    }

    private static final class SalesOrderMappingContext implements FeishuSalesOrderImportMapper.MappingContext {
        private static final Pattern CODE_PREFIX =
                Pattern.compile("^([A-Za-z]{1,12}\\d{2,})\\s*[-_：: ]+.*$");
        private final Map<String, FeishuSalesOrderImportMapper.CustomerMapping> customers = new HashMap<>();
        private final Map<String, FeishuSalesOrderImportMapper.ProductMapping> products = new HashMap<>();
        private final Map<String, FeishuSalesOrderImportMapper.EmployeeMapping> employees = new HashMap<>();
        private final Map<String, String> areaCodes = new HashMap<>();

        SalesOrderMappingContext(Map<String, String> areaCodes) {
            if (areaCodes != null) {
                areaCodes.forEach((name, code) -> add(this.areaCodes, clean(code), name));
            }
        }

        @Override
        public Optional<FeishuSalesOrderImportMapper.CustomerMapping> customer(String... references) {
            return lookup(customers, references);
        }

        @Override
        public Optional<FeishuSalesOrderImportMapper.ProductMapping> product(String... references) {
            return lookup(products, references);
        }

        @Override
        public Optional<FeishuSalesOrderImportMapper.EmployeeMapping> employee(String... references) {
            return lookup(employees, references);
        }

        @Override
        public Optional<String> regionCode(String... references) {
            return lookup(areaCodes, references);
        }

        void addCustomer(StoredRawRow row, ProjectionDecision decision) {
            Long customerId = positiveLong(decision.targetId());
            if (customerId == null) return;
            Map<String, String> values = row.values();
            String customerCode = firstNonBlank(
                    decision.customerCode(),
                    value(values, "客户编码", "门店编码", "商家编号"),
                    codePrefix(row.sourceDocumentNo()),
                    row.sourceDocumentNo());
            String customerName = firstNonBlank(
                    value(values, "门店名称", "商家名称", "客户名称", "门店", "关联门店",
                            "商家编号名称", "门店编码名称"),
                    row.sourceDocumentNo());
            FeishuSalesOrderImportMapper.CustomerMapping mapping =
                    new FeishuSalesOrderImportMapper.CustomerMapping(customerId, customerCode, customerName);
            add(customers, mapping, row.sourceDocumentNo(), customerCode, customerName,
                    value(values, "门店编码", "商家编号", "门店编码名称", "商家编号名称",
                            "关联门店", "关联商家", "门店名称", "商家名称", "客户名称"));
        }

        void addResolvedCustomer(
                StoredRawRow row,
                ExternalCrmCustomerRowCommand command,
                ExternalCrmCustomerSyncRowResult result) {
            if (row == null || command == null || result == null || result.customerId() == null) return;
            Map<String, String> values = row.values();
            String customerCode = firstNonBlank(result.customerCode(), command.sourceCustomerId());
            String customerName = firstNonBlank(command.customerName(),
                    value(values, "门店", "门店名称", "客户名称", "客户"),
                    linkedDisplayName(value(values, "关联门店", "订单编号门店")),
                    customerCode);
            FeishuSalesOrderImportMapper.CustomerMapping mapping =
                    new FeishuSalesOrderImportMapper.CustomerMapping(
                            result.customerId(), customerCode, customerName);
            add(customers, mapping, row.sourceDocumentNo(), command.sourceDocumentNo(),
                    command.sourceCustomerId(), customerCode, customerName,
                    value(values, "关联门店", "门店", "门店名称", "客户名称", "客户",
                            "客户编码", "门店编码", "商家编号", "订单编号门店"));
        }

        void addProduct(StoredRawRow row, ProjectionDecision decision) {
            Long productId = positiveLong(decision.targetId());
            if (productId == null) return;
            Map<String, String> values = row.values();
            String productCode = firstNonBlank(decision.productCode(),
                    value(values, "产品编码", "商品编码", "SKU编码"), row.sourceDocumentNo());
            String productName = firstNonBlank(value(values, "产品名称", "商品名称", "产品编码名称"),
                    productCode);
            String specification = firstNonBlank(value(values, "规格", "产品规格"), productName);
            FeishuSalesOrderImportMapper.ProductMapping mapping =
                    new FeishuSalesOrderImportMapper.ProductMapping(productId, decision.productVariantId(),
                            productCode, decision.variantCode(), productName, specification, decision.unitCode());
            add(products, mapping, row.sourceDocumentNo(), productCode, productName, specification,
                    value(values, "产品编码", "商品编码", "SKU编码", "产品编码名称", "产品名称",
                            "商品名称", "产品编号", "订单产品"));
        }

        void addResolvedProduct(StoredRawRow row, ExternalProductResolvedView resolved) {
            if (row == null || resolved == null || resolved.productId() == null
                    || resolved.productVariantId() == null) {
                return;
            }
            Map<String, String> values = row.values();
            String productCode = firstNonBlank(resolved.productCode(),
                    value(values, "产品编号", "商品编码", "产品编码", "商品编号",
                            "产品编码名称", "产品编号名称"));
            String productName = firstNonBlank(resolved.productName(),
                    value(values, "订单产品", "产品名称", "商品名称", "产品", "商品"));
            String specification = firstNonBlank(resolved.specification(),
                    value(values, "规格", "规格名称", "规格描述", "产品规格"));
            FeishuSalesOrderImportMapper.ProductMapping mapping =
                    new FeishuSalesOrderImportMapper.ProductMapping(
                            resolved.productId(), resolved.productVariantId(), productCode,
                            resolved.variantCode(), productName, specification, resolved.unitCode());
            add(products, mapping, row.sourceDocumentNo(), productCode, resolved.variantCode(),
                    productName, specification,
                    value(values, "产品编号", "商品编码", "产品编码", "SKU编码", "商品编号",
                            "产品编码名称", "产品编号名称", "订单产品", "产品名称", "商品名称", "产品", "商品",
                            "规格", "规格名称", "规格描述", "产品规格"));
        }

        void addResolvedEmployee(ExternalEmployeeResolvedView resolved) {
            if (resolved == null || clean(resolved.employeeCode()) == null) {
                return;
            }
            if (!isSalesAttributionEmployee(resolved)) {
                log.info("飞书HR员工解析跳过非销售归属 employeeName={} employeeCode={} jobCategory={} position={} department={}",
                        clean(resolved.employeeName()), clean(resolved.employeeCode()),
                        clean(resolved.jobCategory()), clean(resolved.positionName()),
                        clean(resolved.departmentName()));
                return;
            }
            FeishuSalesOrderImportMapper.EmployeeMapping mapping =
                    new FeishuSalesOrderImportMapper.EmployeeMapping(
                            text(resolved.employeeCode(), 50),
                            text(resolved.employeeName(), 100));
            add(employees, mapping, resolved.employeeName(), resolved.sourceEmployeeId(), resolved.employeeCode());
        }

        private static boolean isSalesAttributionEmployee(ExternalEmployeeResolvedView resolved) {
            String category = clean(resolved.jobCategory());
            if (category != null) return salesRoleText(category);
            String roleText = String.join(" ",
                    clean(resolved.positionName()) == null ? "" : clean(resolved.positionName()),
                    clean(resolved.departmentName()) == null ? "" : clean(resolved.departmentName()));
            String cleaned = clean(roleText);
            return cleaned == null || salesRoleText(cleaned);
        }

        private static boolean salesRoleText(String value) {
            String text = clean(value);
            if (text == null) return false;
            return text.contains("销售")
                    || text.contains("业务")
                    || text.contains("城市总")
                    || text.contains("客户经理");
        }

        private static <T> Optional<T> lookup(Map<String, T> values, String... references) {
            if (references == null || references.length == 0) return Optional.empty();
            for (String reference : references) {
                for (String key : referenceKeys(reference)) {
                    T value = values.get(key);
                    if (value != null) return Optional.of(value);
                }
            }
            return Optional.empty();
        }

        private static <T> void add(Map<String, T> values, T mapping, String... references) {
            if (mapping == null || references == null) return;
            for (String reference : references) {
                for (String key : referenceKeys(reference)) {
                    values.putIfAbsent(key, mapping);
                }
            }
        }

        private static Set<String> referenceKeys(String value) {
            String text = clean(value);
            if (text == null) return Set.of();
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            addKey(keys, text);
            String repeated = repeatedSingleToken(text);
            if (repeated != null) addKey(keys, repeated);
            String prefix = codePrefix(text);
            if (prefix != null) addKey(keys, prefix);
            if (text.contains(" - ")) {
                String[] parts = text.split("\\s+-\\s+", 2);
                addKey(keys, parts[0]);
                if (parts.length > 1) addKey(keys, parts[1]);
            }
            String[] descriptorParts = text.split("\\s*[-－–—]\\s*");
            if (descriptorParts.length >= 2) {
                for (String part : descriptorParts) {
                    addKey(keys, part);
                }
                if (descriptorParts.length >= 3) {
                    StringBuilder middle = new StringBuilder();
                    for (int index = 1; index < descriptorParts.length - 1; index++) {
                        String part = clean(descriptorParts[index]);
                        if (part == null) continue;
                        if (!middle.isEmpty()) middle.append('-');
                        middle.append(part);
                    }
                    addKey(keys, middle.toString());
                }
            }
            for (String part : text.split("[,，;；\\n\\r]+")) {
                addKey(keys, part);
            }
            return Set.copyOf(keys);
        }

        private static void addKey(Set<String> keys, String value) {
            String key = normalizeReference(value);
            if (key != null) keys.add(key);
        }

        static String normalizeReference(String value) {
            String text = clean(value);
            if (text == null) return null;
            String normalized = text.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            return normalized.isBlank() ? null : normalized;
        }

        private static String codePrefix(String value) {
            String text = clean(value);
            if (text == null) return null;
            Matcher matcher = CODE_PREFIX.matcher(text);
            return matcher.matches() ? matcher.group(1) : null;
        }

        private static Long positiveLong(String value) {
            String text = clean(value);
            if (text == null || !text.matches("\\d+")) return null;
            try {
                long parsed = Long.parseLong(text);
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        private static String value(Map<String, String> values, String... names) {
            if (values == null || names == null) return null;
            for (String name : names) {
                String value = values.get(name);
                if (value != null && !value.isBlank()) return value;
            }
            return null;
        }
    }

    private record CopyResult(long bytes, String sha256) {
    }

    private record DeduplicationResult(List<PreflightRawRow> rows,
                                       long duplicateRows,
                                       List<FeishuImportIssueView> issues) {
    }

    private record IssueDisposition(String category, boolean blocking, String action, String hint) {
    }

    private record DuplicateEvaluation(String scope,
                                       UUID duplicateOfRawRowId,
                                       boolean noChange,
                                       String issueType) {
        private boolean currentBatchDuplicate() {
            return scope != null && scope.startsWith("BATCH_");
        }

        private static DuplicateEvaluation none() {
            return new DuplicateEvaluation(null, null, false, null);
        }
    }

    private static final class ProjectionProgressRecorder {
        private final FeishuImportStore store;
        private final CallerIdentity caller;
        private final Instant updatedAt;
        private final boolean dryRun;
        private final LinkedHashMap<UUID, RowProjectionUpdate> pendingUpdates = new LinkedHashMap<>();
        private final Map<UUID, ProjectionDecision> pendingDecisions = new HashMap<>();
        private final Map<UUID, ProjectionDecision> flushedDecisions = new HashMap<>();

        private ProjectionProgressRecorder(FeishuImportStore store,
                                           CallerIdentity caller,
                                           Instant updatedAt,
                                           boolean dryRun) {
            this.store = store;
            this.caller = caller;
            this.updatedAt = updatedAt;
            this.dryRun = dryRun;
        }

        private void record(StoredRawRow row, ProjectionDecision decision) {
            if (dryRun || store == null || caller == null || row == null || decision == null) return;
            pendingUpdates.put(row.id(), rowProjectionUpdate(caller, row, decision, updatedAt));
            pendingDecisions.put(row.id(), decision);
            if (pendingUpdates.size() >= PROJECTION_PROGRESS_FLUSH_SIZE) {
                flush();
            }
        }

        private boolean wasFlushed(UUID rawRowId, ProjectionDecision decision) {
            if (dryRun || rawRowId == null || decision == null) return false;
            return decision.equals(flushedDecisions.get(rawRowId));
        }

        private void flush() {
            if (pendingUpdates.isEmpty()) return;
            store.updateRawRowProjections(new ArrayList<>(pendingUpdates.values()));
            flushedDecisions.putAll(pendingDecisions);
            pendingUpdates.clear();
            pendingDecisions.clear();
        }
    }

    private record SalesOrderProjectionResult(Map<UUID, ProjectionDecision> decisions,
                                              Map<String, SalesOrderDetailView> ordersBySourceNo) {
    }

    private record SalesOrderWriteResult(ProjectionDecision decision, SalesOrderDetailView detail) {
    }

    private record SalesOrderHeaderAmounts(BigDecimal totalQuantity, BigDecimal originalAmount,
                                           BigDecimal discountAmount, BigDecimal payableAmount) {
    }

    private record CommandRow<T>(StoredRawRow row, T command) {
    }

    private static final class SalesOrderProjectionGroup {
        private final String sourceOrderNo;
        private final List<StoredRawRow> headerRows = new ArrayList<>();
        private final List<SalesOrderLineProjection> lineRows = new ArrayList<>();
        private SalesOrderCommand headerCommand;

        private SalesOrderProjectionGroup(String sourceOrderNo) {
            this.sourceOrderNo = sourceOrderNo;
        }

        private String sourceOrderNo() {
            return sourceOrderNo;
        }

        private void addHeader(StoredRawRow row, SalesOrderCommand command) {
            headerRows.add(row);
            if (headerCommand == null) headerCommand = command;
        }

        private void addLine(StoredRawRow row, FeishuSalesOrderImportMapper.LineProjectionPlan plan) {
            lineRows.add(new SalesOrderLineProjection(row, plan));
        }

        private SalesOrderCommand headerCommand() {
            return headerCommand;
        }

        private List<StoredRawRow> headerRows() {
            return headerRows;
        }

        private List<SalesOrderLineProjection> lineRows() {
            return lineRows;
        }

        private List<StoredRawRow> rows() {
            ArrayList<StoredRawRow> rows = new ArrayList<>(headerRows);
            lineRows.stream().map(SalesOrderLineProjection::row).forEach(rows::add);
            return rows;
        }
    }

    private record SalesOrderLineProjection(
            StoredRawRow row,
            FeishuSalesOrderImportMapper.LineProjectionPlan plan) {
    }

    private static final class SalesOrderLineAccumulator {
        private final Long productId;
        private final Long productVariantId;
        private final String productCodeSnapshot;
        private final String skuCodeSnapshot;
        private final String productNameSnapshot;
        private final String specificationSnapshot;
        private final String unitCode;
        private final List<String> remarks = new ArrayList<>();
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal discountAmount = BigDecimal.ZERO;
        private boolean hasDiscountAmount;

        private SalesOrderLineAccumulator(SalesOrderLineCommand first) {
            this.productId = first.productId();
            this.productVariantId = first.productVariantId();
            this.productCodeSnapshot = first.productCodeSnapshot();
            this.skuCodeSnapshot = first.skuCodeSnapshot();
            this.productNameSnapshot = first.productNameSnapshot();
            this.specificationSnapshot = first.specificationSnapshot();
            this.unitCode = first.unitCode();
        }

        private void add(SalesOrderLineCommand line, BigDecimal lineAmount) {
            quantity = quantity.add(line.quantity());
            amount = amount.add(line.quantity().multiply(line.unitPrice()));
            if (line.discountAmount() != null && line.discountAmount().compareTo(BigDecimal.ZERO) > 0) {
                hasDiscountAmount = true;
                discountAmount = discountAmount.add(line.discountAmount());
            }
            if (line.remark() != null && !line.remark().isBlank() && !remarks.contains(line.remark())) {
                remarks.add(line.remark());
            }
        }

        private SalesOrderLineCommand command() {
            BigDecimal unitPrice = amount.divide(quantity, 6, RoundingMode.HALF_UP);
            return new SalesOrderLineCommand(productId, productVariantId, productCodeSnapshot,
                    skuCodeSnapshot, productNameSnapshot, specificationSnapshot, unitCode,
                    quantity, unitPrice, null, hasDiscountAmount ? discountAmount : null,
                    remarks.isEmpty() ? null : String.join("；", remarks));
        }
    }

    private static final class RunIssueSummaryAccumulator {
        private final String issueCategory;
        private final String projectionStatus;
        private final String targetDomain;
        private final String targetObjectType;
        private final String message;
        private long rowCount;

        private RunIssueSummaryAccumulator(String issueCategory, String projectionStatus,
                                           String targetDomain, String targetObjectType,
                                           String message) {
            this.issueCategory = issueCategory;
            this.projectionStatus = projectionStatus;
            this.targetDomain = targetDomain;
            this.targetObjectType = targetObjectType;
            this.message = message;
        }

        private void add(long count) {
            rowCount += count;
        }

        private long rowCount() {
            return rowCount;
        }

        private FeishuImportRunIssueSummaryView view() {
            return new FeishuImportRunIssueSummaryView(issueCategory, projectionStatus,
                    targetDomain, targetObjectType, message, rowCount);
        }
    }

    private record ProjectionDecision(String status, String targetDomain,
                                      String targetObjectType, String targetId,
                                      String errorCode, String message,
                                      String customerCode,
                                      Long productVariantId, String productCode,
                                      String variantCode, String unitCode) {
        private ProjectionDecision(String status, String targetDomain,
                                   String targetObjectType, String targetId,
                                   String errorCode, String message) {
            this(status, targetDomain, targetObjectType, targetId, errorCode, message,
                    null, null, null, null, null);
        }

        private static ProjectionDecision customer(String targetId,
                                                   ExternalCrmCustomerSyncRowResult rowResult,
                                                   String message) {
            return new ProjectionDecision("PROJECTED", "CRM", "CUSTOMER",
                    targetId, null, message,
                    rowResult == null ? null : rowResult.customerCode(),
                    null, null, null, null);
        }

        private static ProjectionDecision product(String targetId,
                                                  ExternalProductSyncRowResult rowResult,
                                                  String message) {
            return new ProjectionDecision("PROJECTED", "ERP", "PRODUCT",
                    targetId, null, message,
                    null,
                    rowResult == null ? null : rowResult.productVariantId(),
                    rowResult == null ? null : rowResult.productCode(),
                    rowResult == null ? null : rowResult.variantCode(),
                    rowResult == null ? null : rowResult.unitCode());
        }
    }
}
