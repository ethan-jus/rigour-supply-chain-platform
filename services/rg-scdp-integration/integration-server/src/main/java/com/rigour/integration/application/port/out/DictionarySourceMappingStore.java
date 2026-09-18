package com.rigour.integration.application.port.out;

import com.rigour.integration.api.v1.model.DictionarySourceMappingView;
import com.rigour.integration.api.v1.model.DictionarySourceMappingCommand;
import java.util.List;
import java.util.Optional;

/** 租户和来源字段共同限定映射，不以显示名称跨来源覆盖。 */
public interface DictionarySourceMappingStore {
    record Key(String tenant, String system, String scope, String dictionary, String field, String value) { }
    List<DictionarySourceMappingView> list(String tenant, String dictionary);
    Optional<DictionarySourceMappingView> find(String tenant, Long id);
    Optional<DictionarySourceMappingView> find(Key key);
    void observe(Key key, String targetDictionary, String targetCode);
    DictionarySourceMappingView update(String tenant, Long id, DictionarySourceMappingCommand command, String actor);
}
