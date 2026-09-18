package com.rigour.analytics.infrastructure.persistence.scope;

import com.baomidou.mybatisplus.core.MybatisXMLLanguageDriver;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;

import java.util.*;

/** 在生成缓存键之前构建带范围的 SQL 和绑定参数。保留原始动态参数，禁止字符串拼入用户范围。 */
public final class BiScopedLanguageDriver extends MybatisXMLLanguageDriver {
    @Override
    public SqlSource createSqlSource(Configuration c, XNode script, Class<?> type) {
        return wrap(c, super.createSqlSource(c, script, type));
    }

    @Override
    public SqlSource createSqlSource(Configuration c, String script, Class<?> type) {
        return wrap(c, super.createSqlSource(c, script, type));
    }

    private SqlSource wrap(Configuration c, SqlSource source) {
        return parameter -> {
            BoundSql original = source.getBoundSql(parameter);
            String sql = original.getSql().stripLeading();
            if (sql.startsWith("/* supply-scoped */")) return original;
            if (!sql.regionMatches(true, 0, "SELECT", 0, 6)
                    && !sql.regionMatches(true, 0, "WITH", 0, 4)) return original;
            var policy = BiScopePredicates.policy();
            if (policy == null) return original;
            if (sql.matches("(?is).*\\brigour_(crm|order|erp|integration|hr|sales_work)\\s*\\..*"))
                throw new AuthorizationDeniedException("BI 源数据必须通过领域投影读取");
            if (sql.contains("bi_source_") && !BiScopePredicates.unrestricted(policy))
                throw new AuthorizationDeniedException("bi-source-governance-scope");
            var scopes = BiScopePredicates.ctes(policy);
            boolean with = sql.regionMatches(true, 0, "WITH", 0, 4);
            if (with
                    && sql.substring(4)
                            .stripLeading()
                            .toUpperCase(Locale.ROOT)
                            .startsWith("RECURSIVE"))
                throw new AuthorizationDeniedException("未登记的 BI 递归查询");
            String scoped =
                    "/* supply-scoped */ WITH "
                            + scopes.text()
                            + (with ? "," + sql.substring(4) : " " + sql);
            List<ParameterMapping> mappings = new ArrayList<>();
            for (int i = 0; i < scopes.args().size(); i++)
                mappings.add(
                        new ParameterMapping.Builder(c, "__supply_scope_" + i, Object.class)
                                .build());
            mappings.addAll(original.getParameterMappings());
            BoundSql result = new BoundSql(c, scoped, mappings, parameter);
            original.getAdditionalParameters().forEach(result::setAdditionalParameter);
            for (int i = 0; i < scopes.args().size(); i++)
                result.setAdditionalParameter("__supply_scope_" + i, scopes.args().get(i));
            return result;
        };
    }
}
