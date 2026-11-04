package com.rigour.erp.application.service.supply;

import com.rigour.erp.application.port.out.ErpProcurementOrderStore;
import com.rigour.erp.application.port.out.ProcurementContractStorage;
import com.rigour.erp.domain.model.supply.ProcurementContractKey;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 合同附件沿用采购动作与仓库权限，不单独建立附件或合同台账。 */
@Service
public final class ProcurementContractService {
    private final ErpProcurementOrderStore orders;
    private final ProcurementContractStorage storage;

    public ProcurementContractService(
            ErpProcurementOrderStore orders, ProcurementContractStorage storage) {
        this.orders = orders;
        this.storage = storage;
    }

    public String upload(Long id, String filename, String contentType, byte[] content) {
        CallerIdentity actor =
                actor(
                        id == null ? "erp:procurement:create" : "erp:procurement:update",
                        "erp:supply:write");
        String tenant = actor.tenantId().toString();
        if (id != null) {
            var order =
                    orders.procurementOrder(tenant, id).orElseThrow(() -> error("采购订单不存在或无权访问"));
            if (order.sourceSystemCode() != null && !order.sourceSystemCode().isBlank())
                throw error("外部来源采购单不能更换合同");
            if (!"DRAFT".equals(order.statusCode())
                    || "CONFIRMED".equals(order.confirmationStatusCode()))
                throw error("已确认采购单不能更换合同");
        }
        if (content == null || content.length == 0 || content.length > 10 * 1024 * 1024)
            throw error("合同文件不能为空且不得超过10MB");
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String extension =
                filename == null
                        ? ""
                        : filename.substring(filename.lastIndexOf('.') + 1)
                                .toLowerCase(Locale.ROOT);
        boolean valid =
                switch (type) {
                    case "application/pdf" ->
                            extension.equals("pdf")
                                    && starts(content, new int[] {0x25, 0x50, 0x44, 0x46, 0x2d});
                    case "image/jpeg" ->
                            Set.of("jpg", "jpeg").contains(extension)
                                    && starts(content, new int[] {0xff, 0xd8, 0xff});
                    case "image/png" ->
                            extension.equals("png")
                                    && starts(
                                            content,
                                            new int[] {
                                                0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
                                            });
                    case "application/msword" ->
                            extension.equals("doc")
                                    && starts(
                                            content,
                                            new int[] {
                                                0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1
                                            });
                    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                            extension.equals("docx")
                                    && starts(content, new int[] {0x50, 0x4b, 0x03, 0x04});
                    default -> false;
                };
        if (!valid) throw error("合同仅支持内容与格式匹配的PDF、Word、JPG或PNG文件");
        return storage.upload(tenant, actor.principalId().toString(), type, content);
    }

    public String url(Long id) {
        var actor = actor("erp:procurement:read", "erp:supply:read");
        String tenant = actor.tenantId().toString();
        var order = orders.procurementOrder(tenant, id).orElseThrow(() -> error("采购订单不存在或无权访问"));
        if (!ProcurementContractKey.valid(tenant, order.contractFileKey()))
            throw error("采购合同附件不存在");
        return storage.temporaryUrl(order.contractFileKey());
    }

    private static boolean starts(byte[] content, int[] magic) {
        if (content.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if ((content[i] & 255) != magic[i]) return false;
        return true;
    }

    private static CallerIdentity actor(String action, String legacy) {
        var actor = AuthorizationContext.requireCurrent();
        if (actor.tenantId() == null) throw new AuthorizationDeniedException(action);
        AuthorizationContext.requirePermission(
                com.rigour.tenant.iam.client.SupplyAuthorizationContext.current()
                                .map(
                                        com.rigour.tenant.iam.client.SupplyAuthorizationContext
                                                ::active)
                                .orElse(false)
                        ? action
                        : legacy);
        return actor;
    }

    private static BusinessException error(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
