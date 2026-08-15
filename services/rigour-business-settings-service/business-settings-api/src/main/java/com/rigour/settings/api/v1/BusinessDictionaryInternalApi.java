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
     * 将明确白名单字段中首次出现的来源值批量补入当前租户生效字典。
     * 已有条目不会被修改或重新启用，字典定义也不会由同步任务自动创建。
     *
     * @param command 模块、字典和本批次观察到的来源值
     * @return 补齐统计及补齐后的有效字典快照
     */
    @PostMapping("/items/sync")
    ApiResponse<DictSyncResult> syncItems(@RequestBody DictSyncCommand command);
}
