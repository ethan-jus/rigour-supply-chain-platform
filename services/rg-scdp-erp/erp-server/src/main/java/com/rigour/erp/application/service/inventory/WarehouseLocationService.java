package com.rigour.erp.application.service.inventory;

import com.rigour.erp.application.port.out.WarehouseLocationCatalog;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.*;
import org.springframework.stereotype.Service;

/** 所在地按已发布版本校验；地址行政区划不推断业务归属或成本城市。 */
@Service
public final class WarehouseLocationService {
    private final WarehouseLocationCatalog source;
    public WarehouseLocationService(WarehouseLocationCatalog source) { this.source = source; }

    public AdminDivisionCatalog catalog(String version) {
        if (version != null && !version.matches("[A-Za-z0-9_.-]{1,32}")) throw bad("行政区划版本无效");
        var result = source.published(version);
        if (result == null || result.version() == null || result.nodes().isEmpty()
                || version != null && !version.equals(result.version()))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "行政区划目录响应不完整，请稍后重试", List.of());
        return result;
    }

    public void validate(String code, String version) {
        if (code == null && version == null) return;
        if (code != null && code.isBlank() && version == null) return;
        if (code == null || !code.matches("[0-9]{6,12}") || version == null || version.isBlank())
            throw bad("请选择有效的所在地区及行政区划版本");
        var nodes = catalog(version).nodes();
        var node = nodes.stream().filter(n -> code.equals(n.code())).findFirst().orElse(null);
        if (node == null || !"COUNTY".equals(node.level()) || path(nodes, code).isEmpty())
            throw bad("所在地区必须选择已发布目录中的有效区县");
    }

    public static String display(AdminDivisionCatalog catalog, String code) {
        return String.join(" / ", path(catalog.nodes(), code));
    }

    private static List<String> path(List<AdminDivisionCatalog.Node> nodes, String code) {
        var index = new HashMap<String, AdminDivisionCatalog.Node>();
        nodes.forEach(n -> index.put(n.code(), n));
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        while (code != null) {
            var node = index.get(code);
            if (node == null || !seen.add(code)) return List.of();
            result.addFirst(node.name());
            code = node.parentCode();
        }
        return result;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
