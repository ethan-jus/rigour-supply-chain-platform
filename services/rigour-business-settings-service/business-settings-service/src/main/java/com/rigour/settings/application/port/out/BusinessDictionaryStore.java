package com.rigour.settings.application.port.out;

import com.rigour.settings.api.v1.model.DictCommand;
import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 公共业务字典出站端口；所有实现必须在数据库查询阶段执行租户隔离。 */
public interface BusinessDictionaryStore {
    /** 按当前主体可见范围和可选条件查询字典。 */
    List<DictView> list(String principalScope, String currentTenantId, String moduleCode,
                        String scopeType, String requestedTenantId, String status);

    /** 按主键查询字典。 */
    Optional<DictView> find(UUID dictId);

    /** 按作用域、模块和编码查询一本启用字典。 */
    Optional<DictView> findActive(String scopeType, String scopeId, String moduleCode, String code);

    /** 查询指定字典的全部条目，包括禁用项。 */
    List<DictItemView> items(UUID dictId);

    /** 新增字典；存在基础字典时在同一事务复制完整条目树。 */
    DictView create(DictCommand command, String scopeId, String tenantId, String actorId);

    /** 使用命令中的版本号修改字典可变属性。 */
    DictView update(UUID dictId, DictCommand command, String actorId);

    /** 在指定字典中新增条目并根据父节点计算层级。 */
    DictItemView createItem(UUID dictId, DictItemCommand command, String actorId);

    /** 使用乐观锁修改条目，并在父节点变化时更新后代层级。 */
    DictItemView updateItem(UUID itemId, DictItemCommand command, String actorId);

    /**
     * 批量新增当前字典中尚不存在的精确来源值；已有停用项保持不变，只允许把“名称=原值”的
     * 历史占位名称补充为已确认名称。整个批次只递增一次字典修订号。
     */
    SyncStats syncMissingItems(UUID dictId, List<SyncItem> items, String actorId);

    /** 内部同步准备写入的根级启用项。 */
    record SyncItem(String code, String name, String value) { }

    /** 内部同步持久化统计。 */
    record SyncStats(int created, int existing, int blocked, int enriched) { }
}
