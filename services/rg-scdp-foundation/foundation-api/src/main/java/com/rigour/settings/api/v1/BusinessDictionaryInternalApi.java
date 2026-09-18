package com.rigour.settings.api.v1;

import com.rigour.settings.api.v1.model.DictSyncCommand;
import com.rigour.settings.api.v1.model.DictSyncResult;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 公共业务字典内部同步契约。
 *
 * <p>该路径不经过 Gateway 浏览器路由，只接受携带可信服务身份和同步权限的服务间调用。</p>
 */
public interface BusinessDictionaryInternalApi {
    String BASE_PATH = "/internal/v1/business-settings/dictionaries";

    /**
     * 将白名单字段的来源值批量解析为标准字典编码。
     * 仅查询现有标准项及历史别名；未知值交由 Integration 记录为待映射，不新增字典项。
     *
     * @param command 模块、字典和本批次观察到的来源值
     * @return 解析统计、当前字典快照及来源值对应的标准编码
     */
    @PostMapping("/items/sync")
    ApiResponse<DictSyncResult> syncItems(@RequestBody DictSyncCommand command);
}
