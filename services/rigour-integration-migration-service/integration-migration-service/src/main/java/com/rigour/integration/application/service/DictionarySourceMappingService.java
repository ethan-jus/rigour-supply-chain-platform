package com.rigour.integration.application.service;

import com.rigour.integration.application.port.out.DictionarySourceMappingStore;
import com.rigour.integration.application.port.out.DictionarySourceMappingStore.Key;
import com.rigour.integration.api.v1.model.DictionarySourceMappingView;
import com.rigour.integration.api.v1.model.DictionarySourceMappingCommand;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.*;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.*;
import org.springframework.stereotype.Service;

/** 在外部导入边界应用租户映射，未知来源留待处理，不中断原始业务落库。 */
@Service
public class DictionarySourceMappingService {
    private final DictionarySourceMappingStore store;
    private final BusinessDictionaryBatchClient client;
    public DictionarySourceMappingService(DictionarySourceMappingStore store, BusinessDictionaryBatchClient client) {
        this.store=store;this.client=client;
    }
    public List<DictionarySourceMappingView> list(String dictionary) {
        AuthorizationContext.requirePermission("business-settings:dict:read");
        return store.list(tenant(),code(dictionary));
    }
    public DictionarySourceMappingView update(Long id, DictionarySourceMappingCommand command) {
        AuthorizationContext.requirePermission("business-settings:dict:write");
        if(command==null || command.revision()<1) throw bad("映射版本无效");
        var source=store.find(tenant(),id).orElseThrow(()->bad("来源映射不存在"));
        String dictionary=code(command.targetDictionaryCode()), item=code(command.targetItemCode());
        var audit=client.sync(caller(),"MAPPING_VALIDATE",List.of(new Observation(dictionary,source.sourceField(),item,item)));
        boolean valid=audit.resolved().stream().anyMatch(r->dictionary.equals(r.targetDictionaryCode()) && item.equals(r.targetItemCode()));
        if(!valid) throw bad("目标必须是可用标准字典项，请刷新后重试");
        return store.update(tenant(),id,new DictionarySourceMappingCommand(dictionary,item,command.revision()),
                AuthorizationContext.requireCurrent().principalId().toString());
    }
    public Audit sync(CallerIdentity caller, String sourceType, Collection<Observation> observations) {
        try { return syncObserved(caller,sourceType,observations); }
        catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("来源字典治理不可用 sourceType={} errorType={}",sourceType,error.getClass().getSimpleName());
            List<MappingIssue> issues=(observations==null ? java.util.stream.Stream.<Observation>empty() : observations.stream())
                    .filter(Objects::nonNull).filter(o->o.sourceValue()!=null && !o.sourceValue().isBlank())
                    .map(o->new MappingIssue(o.dictionaryCode(),o.fieldCode(),o.sourceValue(),1)).toList();
            return new Audit(issues.size(),Map.of(),issues,List.of());
        }
    }
    /** 管理员主动扫描时显式返回失败，避免把持久化异常报告为扫描成功。 */
    public Audit syncObserved(CallerIdentity caller, String sourceType, Collection<Observation> observations) {
        if(caller==null || caller.tenantId()==null || !"SERVICE".equals(caller.principalScope())) throw bad("来源映射需要租户服务身份");
        String system=sourceType.startsWith("FEISHU") ? "FEISHU" : "DHB";
        List<Observation> source=observations==null ? List.of() : observations.stream().filter(Objects::nonNull)
                .filter(o->o.sourceValue()!=null && !o.sourceValue().isBlank()).toList();
        Map<Key,Observation> prepared=new LinkedHashMap<>();
        for(Observation o:source) {
            Key key=new Key(caller.tenantId().toString(),system,o.sourceScope()==null ? sourceType : o.sourceScope(),
                    o.dictionaryCode(),o.fieldCode(),o.sourceValue());
            if (prepared.containsKey(key)) continue;
            var override=store.find(key).filter(DictionarySourceMappingView::manualOverride);
            prepared.put(key,override.map(m->new Observation(m.targetDictionaryCode(),o.fieldCode(),m.targetItemCode(),m.targetItemCode(),o.sourceScope())).orElse(o));
        }
        Audit audit=client.sync(caller,sourceType,prepared.values());
        List<MappingIssue> issues=new ArrayList<>();List<ResolvedValue> resolved=new ArrayList<>();
        for(var entry:prepared.entrySet()) {
            Key key=entry.getKey();Observation value=entry.getValue();
            var match=audit.resolved().stream().filter(r->r.dictionaryCode().equals(value.dictionaryCode()) && r.sourceValue().equals(value.sourceValue())).findFirst();
            store.observe(key,match.map(ResolvedValue::targetDictionaryCode).orElse(null),match.map(ResolvedValue::targetItemCode).orElse(null));
            if(match.isPresent()) resolved.add(new ResolvedValue(key.dictionary(),key.value(),match.get().targetDictionaryCode(),match.get().targetItemCode()));
            else issues.add(new MappingIssue(key.dictionary(),key.field(),key.value(),1));
        }
        return new Audit(issues.size(),audit.revisions(),issues,resolved);
    }
    private CallerIdentity caller() {
        return BusinessDictionaryBatchClient.serviceCaller("rigour-integration-dictionary-mapping","DICTIONARY_MAPPING",AuthorizationContext.requireCurrent().tenantId());
    }
    private static String tenant() {
        var tenant=AuthorizationContext.requireCurrent().tenantId();
        if(tenant==null) throw bad("来源映射需要租户上下文");
        return tenant.toString();
    }
    private static String code(String value) {
        if(value==null || !value.strip().toUpperCase(Locale.ROOT).matches("[A-Z][A-Z0-9_]{0,49}")) throw bad("字典或条目编码无效");
        return value.strip().toUpperCase(Locale.ROOT);
    }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST,message,List.of()); }
}
