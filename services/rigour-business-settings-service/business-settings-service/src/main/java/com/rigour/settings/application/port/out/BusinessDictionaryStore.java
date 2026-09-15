package com.rigour.settings.application.port.out;

import com.rigour.settings.api.v1.model.DictCommand;
import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictView;
import java.util.List;
import java.util.Optional;

/** 公共业务字典出站端口；按 dictionaryCode 直接读取和维护。 */
public interface BusinessDictionaryStore {
    /** 按可选条件查询未删除字典。 */
    List<DictView> list(String dictionaryType, String dictionaryCode);

    /** 按主键查询字典。 */
    Optional<DictView> find(Long dictionaryId);

    /** 按编码查询一本未删除字典。 */
    Optional<DictView> findByCode(String dictionaryCode);

    /** 查询指定字典的未删除条目。 */
    List<DictItemView> items(String dictionaryCode);

    /** 新增字典。 */
    DictView create(DictCommand command, String actorId);

    /** 使用命令中的修订号修改字典可变属性。 */
    DictView update(Long dictionaryId, DictCommand command, String actorId);

    /** 在指定字典中新增条目并根据父节点计算层级。 */
    DictItemView createItem(Long dictionaryId, DictItemCommand command, String actorId);

    /** 使用乐观锁修改条目，并在父节点变化时更新后代层级。 */
    DictItemView updateItem(Long itemId, DictItemCommand command, String actorId);

    /** 查询字典内引用与合并阻点。 */
    com.rigour.settings.api.v1.model.DictMergePreview previewMerge(Long itemId, Long targetItemId);

    /** 锁定字典后合并并保留操作审计。 */
    com.rigour.settings.api.v1.model.DictMergePreview merge(Long itemId,
            com.rigour.settings.api.v1.model.DictMergeCommand command, String actorId, String tenantId);
}
