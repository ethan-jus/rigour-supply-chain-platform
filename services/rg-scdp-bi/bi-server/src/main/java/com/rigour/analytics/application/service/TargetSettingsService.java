package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.TargetSettingsModels.*;
import com.rigour.analytics.application.port.out.TargetSettingsStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class TargetSettingsService {
    public static final String READ = "analytics:dashboard:read";
    public static final String WRITE = "analytics:targets:write";
    public static final String DEFAULTS_WRITE = "analytics:targets:defaults";
    public static final Set<String> METRICS = Set.of("SALES_AMOUNT", "RECEIPT_AMOUNT", "NEW_CUSTOMER", "REPEAT_CUSTOMER");
    private final TargetSettingsStore store;
    private final BiDataScopeService scope;
    private final Clock clock;
    public TargetSettingsService(TargetSettingsStore store, BiDataScopeService scope, Clock clock) {
        this.store=store; this.scope=scope; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Settings settings(String month) {
        var actor=actor(READ); month(month);
        var subjects=visibleSubjects(actor.tenantId().toString());
        var keys=new HashSet<String>();
        subjects.forEach(s -> keys.add(s.dimensionType()+":"+s.code()));
        return new Settings(month, subjects, store.overrides(actor.tenantId().toString(), month).stream()
                .filter(o -> keys.contains(o.dimensionType()+":"+o.code())).toList(),
                store.defaults(actor.tenantId().toString()), defaultsAllowed());
    }
    private List<Subject> visibleSubjects(String tenant) {
        var result=new ArrayList<Subject>();
        for (var s:store.subjects(tenant)) {
            try { checkScope(s, READ); } catch (AuthorizationDeniedException ignored) { continue; }
            boolean writable=true;
            try { AuthorizationContext.requirePermission(WRITE); checkScope(s, WRITE); }
            catch (AuthorizationDeniedException ignored) { writable=false; }
            result.add(new Subject(s.dimensionType(),s.code(),s.name(),s.cityCode(),s.cityName(),
                    s.departmentName(),s.employmentStatus(),writable));
        }
        return result;
    }
    private void checkScope(Subject s, String action) {
        if (!store.permitted(AuthorizationContext.requireCurrent().tenantId().toString(),s.dimensionType(),s.code(),action))
            throw new AuthorizationDeniedException("bi-target-scope");
    }
    @Transactional
    public void save(Batch batch) {
        var actor=actor(WRITE);
        if (batch==null || batch.changes()==null || batch.changes().isEmpty() || batch.changes().size()>800)
            throw invalid("请选择1至800项指标变更");
        var period=month(batch.month());
        String reason=reason(batch.reason(),!period.isAfter(YearMonth.now(clock.withZone(ZoneId.of("Asia/Shanghai")))));
        var subjects=new HashMap<String,Subject>();
        store.subjects(actor.tenantId().toString()).forEach(s -> subjects.put(s.dimensionType()+":"+s.code(),s));
        var seen=new HashSet<String>();
        for (var c:batch.changes()) {
            if (c==null) throw invalid("指标不能为空");
            validate(c.metric(),c.value(),c.expectedRevision(),true);
            if (!seen.add(c.dimensionType()+":"+c.code()+":"+c.metric())) throw invalid("重复的目标指标");
            var s=subjects.get(c.dimensionType()+":"+c.code());
            if (s==null) throw invalid("城市或销售已不存在，请刷新列表");
            checkScope(s,WRITE);
        }
        for (var c:batch.changes()) store.save(actor.tenantId().toString(),actor.userId().toString(),
                batch.month(),subjects.get(c.dimensionType()+":"+c.code()),c,reason,clock.instant());
    }
    @Transactional
    public void saveDefaults(DefaultsBatch batch) {
        var actor=actor(DEFAULTS_WRITE); requireDefaults();
        if (batch==null || batch.dimensionType()==null || !Set.of("CITY","SALES_OWNER").contains(batch.dimensionType())
                || batch.changes()==null || batch.changes().isEmpty() || batch.changes().size()>4)
            throw invalid("请选择默认指标");
        // Effective dates prevent a new standard from silently rewriting historical periods.
        if (!month(batch.effectiveMonth()).isAfter(YearMonth.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))
            throw invalid("默认标准从下月或之后生效，历史及当月目标请单独调整");
        var reason=reason(batch.reason(),true);
        var seen=new HashSet<String>();
        for (var c:batch.changes()) {
            if (c==null) throw invalid("指标不能为空");
            validate(c.metric(),c.value(),c.expectedRevision(),false);
            if (!seen.add(c.metric())) throw invalid("重复的默认指标");
        }
        for (var c:batch.changes()) store.saveDefault(actor.tenantId().toString(),actor.userId().toString(),
                batch.effectiveMonth(),batch.dimensionType(),c,reason,clock.instant());
    }
    @Transactional(readOnly=true)
    public List<History> history(String month,String type,String code) {
        var actor=actor(READ); month(month);
        if ("DEFAULT".equals(code)) { requireDefaults(); }
        else {
            var s=store.subjects(actor.tenantId().toString()).stream()
                    .filter(x -> x.dimensionType().equals(type)&&x.code().equals(code)).findFirst()
                    .orElseThrow(() -> invalid("目标对象不存在"));
            checkScope(s,READ);
        }
        return store.history(actor.tenantId().toString(),month,type,code);
    }
    private void requireDefaults() {
        AuthorizationContext.requirePermission(DEFAULTS_WRITE);
        scope.requireGlobalGovernance();
        scope.requireObjectActionScope(null,null,DEFAULTS_WRITE);
    }
    private boolean defaultsAllowed() {
        try { requireDefaults(); return true; } catch (AuthorizationDeniedException e) { return false; }
    }
    private static CallerIdentity actor(String action) {
        var actor=AuthorizationContext.requireCurrent();
        if (actor.tenantId()==null || actor.userId()==null) throw new AuthorizationDeniedException("tenant-user");
        AuthorizationContext.requirePermission(READ); AuthorizationContext.requirePermission(action); return actor;
    }
    private static YearMonth month(String month) {
        try { var value=YearMonth.parse(month); if (value.getYear()<2000 || value.getYear()>2100) throw invalid("月份超出范围"); return value; }
        catch (java.time.format.DateTimeParseException | NullPointerException e) { throw invalid("月份格式应为YYYY-MM"); }
    }
    private static String reason(String value,boolean required) {
        String text=value==null ? "" : value.strip();
        if ((required&&text.isEmpty()) || text.length()>1000) throw invalid("请填写修改原因，最多1000字"); return text;
    }
    private static void validate(String metric,BigDecimal value,int revision,boolean nullable) {
        if (metric==null || !METRICS.contains(metric) || revision<0) throw invalid("指标或版本无效");
        if (value==null) { if(nullable) return; throw invalid("默认指标不能为空"); }
        if(value.signum()<0 || value.compareTo(new BigDecimal("999999999999.99"))>0
                || value.stripTrailingZeros().scale()>(metric.endsWith("CUSTOMER") ? 0 : 2))
            throw invalid("金额须为非负数且最多两位小数，客户数须为非负整数");
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST,message,List.of()); }
}
