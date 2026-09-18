package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.shared.context.AuthorizationDeniedException;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;

import java.util.*;

/** JDBC 看板复用完整角色范围和成员上限；绑定参数先于统计聚合，不能只依靠页面筛选。 */
public final class BiScopedQueries {
    private BiScopedQueries() {}

    private record Query(String sql, MapSqlParameterSource args) {}

    private static Query scoped(String sql, MapSqlParameterSource input) {
        var policy = BiScopePredicates.policy();
        if (policy == null) return new Query(sql, input);
        if (!policy.functionAllowed())
            throw new AuthorizationDeniedException("analytics:dashboard:read");
        var ctes = BiScopePredicates.ctes(policy);
        var args = new MapSqlParameterSource(input.getValues());
        StringBuilder prefix = new StringBuilder();
        int i = 0;
        for (char ch : ctes.text().toCharArray()) {
            if (ch == '?') {
                String name = "__supply_scope_" + i;
                prefix.append(':').append(name);
                args.addValue(name, ctes.args().get(i++));
            } else prefix.append(ch);
        }
        String body = sql.stripLeading();
        boolean with = body.regionMatches(true, 0, "WITH", 0, 4);
        if (with && body.substring(4).stripLeading().startsWith("RECURSIVE"))
            throw new AuthorizationDeniedException("bi-recursive-query");
        return new Query("WITH " + prefix + (with ? "," + body.substring(4) : " " + body), args);
    }

    public static <T> List<T> query(
            NamedParameterJdbcTemplate jdbc,
            String sql,
            MapSqlParameterSource args,
            RowMapper<T> mapper) {
        var query = scoped(sql, args);
        return jdbc.query(query.sql(), query.args(), mapper);
    }

    public static <T> T queryForObject(
            NamedParameterJdbcTemplate jdbc,
            String sql,
            MapSqlParameterSource args,
            RowMapper<T> mapper) {
        var query = scoped(sql, args);
        return jdbc.queryForObject(query.sql(), query.args(), mapper);
    }
}
