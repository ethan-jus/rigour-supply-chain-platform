package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.OrderInvoiceModels.InvoiceAttachmentView;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceApplyCommand;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceCompleteCommand;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoicePageView;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceProfileView;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterOrderView;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.application.port.out.InvoiceAttachmentStorage;
import com.rigour.order.application.port.out.OrderInvoiceStore;
import com.rigour.order.application.port.out.OrderInvoiceStore.InvoicePageCriteria;
import com.rigour.order.application.port.out.OrderInvoiceStore.OrderInvoiceProfileRow;
import com.rigour.order.application.port.out.OrderInvoiceStore.OrderInvoiceRow;
import com.rigour.order.application.port.out.OrderRegisterStore;
import com.rigour.order.domain.invoice.OrderInvoiceStatus;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 开票登记用例：一单一票，无记录=未申请，申请后=待开票，上传附件并完成开票=已开票，撤回保留痕迹。
 * 发票只按内部订单关联，不参与订货宝同步；金额取申请时的订单金额快照。
 */
@Service
public class OrderInvoiceService {
    private static final Logger log = LoggerFactory.getLogger(OrderInvoiceService.class);
    static final String READ_PERMISSION = "order:read";
    static final String WRITE_PERMISSION = "order:invoice:write";
    static final int MAX_ATTACHMENTS = 5;
    static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "application/pdf");
    private static final Set<String> TITLE_TYPES = Set.of("COMPANY", "PERSONAL");
    private static final Set<String> INVOICE_TYPES = Set.of("NORMAL", "SPECIAL");
    private static final Set<String> PAGE_STATUSES = Set.of("PENDING", "INVOICED", "REVOKED");

    private final OrderInvoiceStore invoiceStore;
    private final OrderRegisterStore orderStore;
    private final InvoiceAttachmentStorage attachmentStorage;
    private final FundAttachmentUrlResolver urlResolver;

    public OrderInvoiceService(
            OrderInvoiceStore invoiceStore,
            OrderRegisterStore orderStore,
            InvoiceAttachmentStorage attachmentStorage,
            ObjectProvider<FundAttachmentUrlResolver> urlResolverProvider) {
        this.invoiceStore = invoiceStore;
        this.orderStore = orderStore;
        this.attachmentStorage = attachmentStorage;
        this.urlResolver = urlResolverProvider.getIfAvailable(() -> FundAttachmentUrlResolver.NONE);
    }

    /** 查订单的发票登记；未申请/已撤回没有记录时返回空数据，前端按未申请展示。 */
    public OrderInvoiceView view(String orderNo) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        String normalized = text(orderNo, 80, "orderNo");
        if (normalized == null) throw badRequest("orderNo不能为空");
        return invoiceStore.findByOrderNo(tenantId, normalized)
                .map(row -> toView(tenantId, row))
                .orElse(null);
    }

    /** 发票管理分页；财务按状态/订单号/客户/申请时间集中核对，写操作仍在发票详情完成。 */
    public OrderInvoicePageView page(
            int begin,
            int step,
            String status,
            String orderNo,
            String customerName,
            Instant appliedFrom,
            Instant appliedTo) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        String normalizedStatus =
                status == null || status.isBlank() ? null : enumValue(status, PAGE_STATUSES, "status");
        requireRange(appliedFrom, appliedTo, "appliedFrom不能晚于appliedTo");
        InvoicePageCriteria criteria =
                new InvoicePageCriteria(
                        normalizedStatus,
                        text(orderNo, 80, "orderNo"),
                        text(customerName, 200, "customerName"),
                        appliedFrom,
                        appliedTo);
        return new OrderInvoicePageView(
                invoiceStore.page(tenantId, pageBegin(begin), pageStep(step), criteria),
                invoiceStore.statusCounts(tenantId, criteria));
    }

    /** 申请或重新申请开票；已开票直接拒绝，待开票修改资料，已撤回重新申请。 */
    public OrderInvoiceView apply(OrderInvoiceApplyCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        if (command == null) throw badRequest("开票申请参数不能为空");
        String orderNo = text(command.orderNo(), 80, "orderNo");
        if (orderNo == null) throw badRequest("orderNo不能为空");
        String titleType = enumValue(command.titleType(), TITLE_TYPES, "titleType");
        String invoiceType = enumValue(command.invoiceType(), INVOICE_TYPES, "invoiceType");
        String title = required(command.title(), 200, "title");
        String taxNo = text(command.taxNo(), 64, "taxNo");
        if ("COMPANY".equals(titleType) && taxNo == null) {
            throw badRequest("企业抬头必须填写纳税人识别号");
        }
        String bankName = text(command.bankName(), 200, "bankName");
        String bankAccount = text(command.bankAccount(), 64, "bankAccount");
        String registerAddress = text(command.registerAddress(), 300, "registerAddress");
        String registerPhone = text(command.registerPhone(), 64, "registerPhone");
        if ("SPECIAL".equals(invoiceType)
                && (taxNo == null || bankName == null || bankAccount == null
                        || registerAddress == null || registerPhone == null)) {
            throw badRequest("专票必须填写税号、开户行、银行账号、注册地址和注册电话");
        }
        String email = text(command.email(), 200, "email");
        String remark = text(command.remark(), 500, "remark");

        OrderRegisterOrderView order =
                orderStore
                        .findOrder(tenantId, orderNo)
                        .orElseThrow(() -> notFound("订单不存在，无法登记开票"));
        var existing = invoiceStore.findByOrderId(tenantId, order.id());
        if (existing.isPresent()) {
            OrderInvoiceStatus status = OrderInvoiceStatus.fromCode(existing.get().status());
            if (status == OrderInvoiceStatus.INVOICED) {
                throw new BusinessException(
                        ErrorCode.ORDER_INVOICE_STATE_CONFLICT, "订单已开票，不能重复申请", List.of());
            }
        }
        String actorId = actorId(actor);
        Instant now = Instant.now();
        List<String> attachmentKeys =
                existing
                        .filter(row -> OrderInvoiceStatus.PENDING.code().equals(row.status()))
                        .map(OrderInvoiceRow::attachmentKeys)
                        .orElse(List.of());
        OrderInvoiceRow draft =
                new OrderInvoiceRow(
                        existing.map(OrderInvoiceRow::id).orElse(null),
                        order.id(),
                        orderNo,
                        order.customerId(),
                        order.customerCode(),
                        OrderInvoiceStatus.PENDING.code(),
                        titleType,
                        title,
                        taxNo,
                        invoiceType,
                        bankName,
                        bankAccount,
                        registerAddress,
                        registerPhone,
                        email,
                        remark,
                        order.payableAmount(),
                        attachmentKeys,
                        null,
                        actorId,
                        now,
                        null,
                        null,
                        actorId,
                        now);
        OrderInvoiceView view = toView(tenantId, invoiceStore.save(tenantId, draft, actorId));
        rememberCustomerProfile(tenantId, order.customerId(), order.customerCode(), draft, actorId);
        return view;
    }

    /** 上传发票附件；只在待开票状态允许，最多 5 个，单个不超过 10MB。 */
    public OrderInvoiceView uploadAttachments(Long id, List<MultipartFile> files) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        OrderInvoiceRow row = requireRow(tenantId, id);
        requirePending(row, "只有待开票状态可以上传发票附件");
        List<MultipartFile> uploads =
                files == null
                        ? List.of()
                        : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (uploads.isEmpty()) throw attachmentInvalid("请选择发票附件");
        if (row.attachmentKeys().size() + uploads.size() > MAX_ATTACHMENTS) {
            throw attachmentInvalid("发票附件最多" + MAX_ATTACHMENTS + "个");
        }
        String actorId = actorId(actor);
        List<String> keys = new ArrayList<>(row.attachmentKeys());
        for (MultipartFile file : uploads) {
            String contentType = contentTypeOf(file);
            if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw attachmentInvalid("发票附件仅支持JPG、PNG、PDF");
            }
            if (file.getSize() > MAX_ATTACHMENT_BYTES) {
                throw attachmentInvalid("单个发票附件不能超过10MB");
            }
            byte[] content;
            try {
                content = file.getBytes();
            } catch (IOException exception) {
                throw attachmentInvalid("发票附件读取失败，请重试");
            }
            keys.add(
                    attachmentStorage.upload(
                            tenantId, row.orderNo(), file.getOriginalFilename(), contentType, content));
        }
        OrderInvoiceRow updated =
                new OrderInvoiceRow(
                        row.id(),
                        row.salesOrderId(),
                        row.orderNo(),
                        row.customerId(),
                        row.customerCode(),
                        row.status(),
                        row.titleType(),
                        row.title(),
                        row.taxNo(),
                        row.invoiceType(),
                        row.bankName(),
                        row.bankAccount(),
                        row.registerAddress(),
                        row.registerPhone(),
                        row.email(),
                        row.remark(),
                        row.amount(),
                        List.copyOf(keys),
                        row.invoiceNo(),
                        row.appliedBy(),
                        row.appliedAt(),
                        row.invoicedBy(),
                        row.invoicedAt(),
                        actorId,
                        Instant.now());
        return toView(tenantId, invoiceStore.save(tenantId, updated, actorId));
    }

    /** 完成开票：必须有附件并填写发票号码和开票日期。 */
    public OrderInvoiceView complete(Long id, OrderInvoiceCompleteCommand command) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        OrderInvoiceRow row = requireRow(tenantId, id);
        requirePending(row, "只有待开票状态可以完成开票");
        if (row.attachmentKeys().isEmpty()) {
            throw attachmentInvalid("请先上传发票附件再完成开票");
        }
        String invoiceNo = required(command == null ? null : command.invoiceNo(), 64, "invoiceNo");
        Instant invoicedAt = command == null ? null : command.invoicedAt();
        if (invoicedAt == null) throw badRequest("开票日期不能为空");
        String actorId = actorId(actor);
        OrderInvoiceRow updated =
                new OrderInvoiceRow(
                        row.id(),
                        row.salesOrderId(),
                        row.orderNo(),
                        row.customerId(),
                        row.customerCode(),
                        OrderInvoiceStatus.INVOICED.code(),
                        row.titleType(),
                        row.title(),
                        row.taxNo(),
                        row.invoiceType(),
                        row.bankName(),
                        row.bankAccount(),
                        row.registerAddress(),
                        row.registerPhone(),
                        row.email(),
                        row.remark(),
                        row.amount(),
                        row.attachmentKeys(),
                        invoiceNo,
                        row.appliedBy(),
                        row.appliedAt(),
                        actorId,
                        invoicedAt,
                        actorId,
                        Instant.now());
        OrderInvoiceView view = toView(tenantId, invoiceStore.save(tenantId, updated, actorId));
        log.info(
                "订单开票完成 tenantId={} orderNo={} invoiceNo={}",
                tenantId,
                row.orderNo(),
                invoiceNo);
        return view;
    }

    /** 撤回申请：仅待开票允许，保留已撤回痕迹，可再次申请。 */
    public OrderInvoiceView withdraw(Long id) {
        CallerIdentity actor = actor(WRITE_PERMISSION);
        String tenantId = actor.tenantId().toString();
        OrderInvoiceRow row = requireRow(tenantId, id);
        requirePending(row, "只有待开票状态可以撤回申请");
        String actorId = actorId(actor);
        OrderInvoiceRow updated =
                new OrderInvoiceRow(
                        row.id(),
                        row.salesOrderId(),
                        row.orderNo(),
                        row.customerId(),
                        row.customerCode(),
                        OrderInvoiceStatus.REVOKED.code(),
                        row.titleType(),
                        row.title(),
                        row.taxNo(),
                        row.invoiceType(),
                        row.bankName(),
                        row.bankAccount(),
                        row.registerAddress(),
                        row.registerPhone(),
                        row.email(),
                        row.remark(),
                        row.amount(),
                        row.attachmentKeys(),
                        null,
                        row.appliedBy(),
                        row.appliedAt(),
                        null,
                        null,
                        actorId,
                        Instant.now());
        return toView(tenantId, invoiceStore.save(tenantId, updated, actorId));
    }

    private OrderInvoiceRow requireRow(String tenantId, Long id) {
        if (id == null || id < 1) throw badRequest("发票ID无效");
        return invoiceStore.findById(tenantId, id).orElseThrow(() -> notFound("发票记录不存在"));
    }

    /** 客户开票资料列表；最近使用优先，用于申请弹窗下拉选择与回显。 */
    public List<OrderInvoiceProfileView> profilesByOrderNo(String orderNo) {
        CallerIdentity actor = actor(READ_PERMISSION);
        String tenantId = actor.tenantId().toString();
        String normalized = text(orderNo, 80, "orderNo");
        if (normalized == null) throw badRequest("orderNo不能为空");
        return orderStore
                .findOrder(tenantId, normalized)
                .filter(order -> order.customerId() != null)
                .map(order -> invoiceStore.profilesByCustomer(tenantId, order.customerId()).stream()
                        .map(OrderInvoiceService::toProfileView)
                        .toList())
                .orElse(List.of());
    }

    /** 申请成功后把资料沉淀为该客户的开票资料；失败只记日志，不影响发票登记。 */
    private void rememberCustomerProfile(
            String tenantId, Long customerId, String customerCode, OrderInvoiceRow row, String actorId) {
        if (customerId == null) return;
        try {
            invoiceStore.saveProfile(
                    tenantId,
                    new OrderInvoiceProfileRow(
                            null,
                            customerId,
                            customerCode,
                            row.titleType(),
                            row.title(),
                            row.taxNo(),
                            row.invoiceType(),
                            row.bankName(),
                            row.bankAccount(),
                            row.registerAddress(),
                            row.registerPhone(),
                            row.email(),
                            row.remark(),
                            null),
                    actorId);
        } catch (RuntimeException exception) {
            log.warn(
                    "客户开票资料沉淀失败，不影响发票登记 tenantId={} customerId={} errorType={}",
                    tenantId,
                    customerId,
                    exception.getClass().getSimpleName());
        }
    }

    private static OrderInvoiceProfileView toProfileView(OrderInvoiceProfileRow row) {
        return new OrderInvoiceProfileView(
                row.id(),
                row.titleType(),
                titleTypeName(row.titleType()),
                row.title(),
                row.taxNo(),
                row.invoiceType(),
                invoiceTypeName(row.invoiceType()),
                row.bankName(),
                row.bankAccount(),
                row.registerAddress(),
                row.registerPhone(),
                row.email(),
                row.remark(),
                row.lastUsedAt());
    }

    private OrderInvoiceView toView(String tenantId, OrderInvoiceRow row) {
        List<InvoiceAttachmentView> attachments =
                row.attachmentKeys().stream()
                        .map(
                                key ->
                                        new InvoiceAttachmentView(
                                                key,
                                                attachmentFileName(key),
                                                temporaryUrl(tenantId, key)))
                        .toList();
        OrderInvoiceStatus status = OrderInvoiceStatus.fromCode(row.status());
        return new OrderInvoiceView(
                row.id(),
                row.salesOrderId(),
                row.orderNo(),
                status == null ? OrderInvoiceStatus.NOT_APPLIED_CODE : status.code(),
                status == null ? OrderInvoiceStatus.NOT_APPLIED_NAME : status.displayName(),
                row.titleType(),
                titleTypeName(row.titleType()),
                row.title(),
                row.taxNo(),
                row.invoiceType(),
                invoiceTypeName(row.invoiceType()),
                row.bankName(),
                row.bankAccount(),
                row.registerAddress(),
                row.registerPhone(),
                row.email(),
                row.remark(),
                row.amount(),
                attachments,
                row.invoiceNo(),
                row.appliedBy(),
                row.appliedAt(),
                row.invoicedBy(),
                row.invoicedAt(),
                row.updatedBy(),
                row.updatedAt());
    }

    private String temporaryUrl(String tenantId, String objectKey) {
        if (objectKey == null || !objectKey.startsWith(tenantId + "/")) return null;
        try {
            return urlResolver.temporaryUrl(tenantId, objectKey);
        } catch (RuntimeException exception) {
            log.debug(
                    "发票附件临时URL生成失败 tenantId={} errorType={}",
                    tenantId,
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private static void requirePending(OrderInvoiceRow row, String message) {
        if (!OrderInvoiceStatus.PENDING.code().equals(row.status())) {
            throw new BusinessException(ErrorCode.ORDER_INVOICE_STATE_CONFLICT, message, List.of());
        }
    }

    private static String contentTypeOf(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) return "";
        return contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
    }

    private static String attachmentFileName(String objectKey) {
        String value = objectKey == null ? "" : objectKey.strip();
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 && slash < value.length() - 1 ? value.substring(slash + 1) : value;
    }

    private static String titleTypeName(String titleType) {
        return switch (titleType == null ? "" : titleType) {
            case "COMPANY" -> "企业";
            case "PERSONAL" -> "个人";
            default -> null;
        };
    }

    private static String invoiceTypeName(String invoiceType) {
        return switch (invoiceType == null ? "" : invoiceType) {
            case "NORMAL" -> "普通发票";
            case "SPECIAL" -> "专用发票";
            default -> null;
        };
    }

    private static CallerIdentity actor(String permission) {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private static String actorId(CallerIdentity actor) {
        return actor.principalId() == null ? null : actor.principalId().toString();
    }

    private static String text(String value, int max, String name) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static String required(String value, int max, String name) {
        String normalized = text(value, max, name);
        if (normalized == null) throw badRequest(name + "不能为空");
        return normalized;
    }

    private static String enumValue(String value, Set<String> allowed, String name) {
        String normalized = required(value, 32, name).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw badRequest(name + "无效");
        return normalized;
    }

    private static int pageBegin(int value) {
        if (value < 0) throw badRequest("begin必须大于等于0");
        return value;
    }

    private static int pageStep(int value) {
        if (value < 1 || value > 200) throw badRequest("step必须在1到200之间");
        return value;
    }

    private static void requireRange(Instant from, Instant to, String message) {
        if (from != null && to != null && !from.isBefore(to)) throw badRequest(message);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, List.of());
    }

    private static BusinessException attachmentInvalid(String message) {
        return new BusinessException(ErrorCode.ORDER_INVOICE_ATTACHMENT_INVALID, message, List.of());
    }
}
