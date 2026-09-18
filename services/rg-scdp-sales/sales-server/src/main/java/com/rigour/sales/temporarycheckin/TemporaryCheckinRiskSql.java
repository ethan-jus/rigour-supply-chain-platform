package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 后台列表、日统计和导出共用关联口径；先隔离租户/授权城市，再施加当前页面筛选。 */
final class TemporaryCheckinRiskSql {
    private TemporaryCheckinRiskSql() { }

    static StringBuilder prepare(StringBuilder source, List<Object> arguments, UUID tenant,
            TemporaryCheckinRepository.AdminReadOptions options) {
        if (!options.needsRisk()) return source;
        List<Object> scopeArguments = new ArrayList<>();
        scopeArguments.add(SalesUuidCodec.encode(tenant));
        scopeArguments.add(options.scopeCity());
        scopeArguments.add(options.scopeCity());
        String ctes = ctes();
        if (options.riskReviewStatus() != null) {
            ctes += reviewCtes();
            scopeArguments.add(SalesUuidCodec.encode(tenant));
            scopeArguments.add(options.scopeCity() == null ? "ALL" : "CITY:" + options.scopeCity());
            scopeArguments.add(TemporaryCheckinRiskModels.RULES_VERSION);
        }
        String sql = source.toString();
        if (!sql.startsWith("WITH visit_ranks AS")) throw new IllegalArgumentException("关联查询需要拜访排序 CTE");
        sql = "WITH " + ctes + ",\n" + sql.substring("WITH ".length());
        sql = sql.replace("WHERE s.tenant_id=?", "LEFT JOIN risk_submission rf ON rf.id=s.id\n"
                + (options.riskReviewStatus() == null ? "" : "LEFT JOIN risk_submission_reviews rr ON rr.submission_id=s.id\n")
                + "WHERE s.tenant_id=?");
        arguments.addAll(0, scopeArguments);
        return new StringBuilder(sql);
    }

    private static String ctes() {
        return """
                risk_visible AS (
                    SELECT v.id,v.tenant_id,v.device_token_hash,v.salesperson_id,v.store_id,v.submitted_at
                    FROM temp_sales_checkin_submission v
                    WHERE v.tenant_id=? AND (? IS NULL OR v.city=?)
                      AND v.status='SUBMITTED' AND v.deletion_state='NONE'
                ), risk_device_counts AS (
                    SELECT v.device_token_hash AS token_hash,d.id AS device_id,
                           CONCAT('DEV-',LPAD(d.id,GREATEST(6,LENGTH(d.id)),'0')) AS device_code,
                           COUNT(*) AS visit_count,COUNT(DISTINCT v.salesperson_id) AS salesperson_count
                    FROM risk_visible v LEFT JOIN temp_sales_checkin_risk_device d
                      ON d.tenant_id=v.tenant_id AND d.token_hash=v.device_token_hash AND d.hash_version='V1'
                    WHERE v.device_token_hash IS NOT NULL
                    GROUP BY v.device_token_hash,d.id
                ), risk_audio_members AS (
                    SELECT DISTINCT a.submission_id,a.segment_id,a.sha256,a.size_bytes,
                           v.salesperson_id,v.submitted_at,g.id AS audio_id,
                           CONCAT('AUD-',LPAD(g.id,GREATEST(6,LENGTH(g.id)),'0')) AS audio_code
                    FROM risk_visible v JOIN temp_sales_checkin_risk_audio_source a
                      ON a.tenant_id=v.tenant_id AND a.submission_id=v.id
                    LEFT JOIN temp_sales_checkin_risk_audio g
                      ON g.tenant_id=a.tenant_id AND g.sha256=a.sha256 AND g.size_bytes=a.size_bytes
                ), risk_audio_counts AS (
                    SELECT sha256,size_bytes,audio_id,audio_code,COUNT(DISTINCT submission_id) AS visit_count,
                           COUNT(DISTINCT salesperson_id) AS salesperson_count,
                           COUNT(DISTINCT DATE(TIMESTAMPADD(HOUR,8,submitted_at))) AS date_count
                    FROM risk_audio_members GROUP BY sha256,size_bytes,audio_id,audio_code
                ), risk_submission_audio AS (
                    SELECT a.submission_id,MAX(c.visit_count-1) AS duplicate_count,
                           MAX(c.salesperson_count>1) AS cross_sales,MAX(c.date_count>1) AS cross_date,
                           GROUP_CONCAT(DISTINCT c.audio_code ORDER BY c.audio_code SEPARATOR ',') AS audio_codes
                    FROM risk_audio_members a JOIN risk_audio_counts c
                      ON c.sha256=a.sha256 AND c.size_bytes=a.size_bytes
                    GROUP BY a.submission_id
                ), risk_submission AS (
                    SELECT v.id,d.device_id,d.device_code,COALESCE(d.visit_count,0) AS device_visit_count,
                           COALESCE(d.salesperson_count,0) AS device_salesperson_count,
                           COALESCE(a.duplicate_count,0) AS audio_duplicate_count,
                           COALESCE(a.cross_sales,0) AS audio_cross_sales,COALESCE(a.cross_date,0) AS audio_cross_date,
                           a.audio_codes
                    FROM risk_visible v LEFT JOIN risk_device_counts d ON d.token_hash=v.device_token_hash
                    LEFT JOIN risk_submission_audio a ON a.submission_id=v.id
                )
                """;
    }

    private static String reviewCtes() {
        return """
                , risk_group_members AS (
                    SELECT 'DEVICE' AS group_kind,d.device_id AS group_id,v.id AS submission_id,
                           LOWER(HEX(v.id)) AS member_key
                    FROM risk_visible v JOIN risk_device_counts d ON d.token_hash=v.device_token_hash
                    WHERE d.salesperson_count>1 AND d.device_id IS NOT NULL
                    UNION ALL
                    SELECT 'AUDIO',a.audio_id,a.submission_id,CONCAT(LOWER(HEX(a.submission_id)),':',LOWER(a.segment_id))
                    FROM risk_audio_members a JOIN risk_audio_counts c
                      ON c.sha256=a.sha256 AND c.size_bytes=a.size_bytes
                    WHERE c.visit_count>1 AND a.audio_id IS NOT NULL
                ), risk_latest_reviews AS (
                    SELECT e.*,ROW_NUMBER() OVER(PARTITION BY group_kind,group_id ORDER BY reviewed_at DESC,id DESC) AS rank_no
                    FROM temp_sales_checkin_risk_review e WHERE e.tenant_id=? AND e.scope_key=?
                ), risk_group_states AS (
                    SELECT m.group_kind,m.group_id,
                           CASE WHEN e.id IS NULL OR e.rules_version<>? OR e.member_count<>COUNT(DISTINCT m.member_key)
                                  OR SUM(CASE WHEN em.member_key IS NULL THEN 1 ELSE 0 END)>0
                                THEN 'PENDING' ELSE e.status END AS review_status
                    FROM risk_group_members m LEFT JOIN risk_latest_reviews e
                      ON e.group_kind=m.group_kind AND e.group_id=m.group_id AND e.rank_no=1
                    LEFT JOIN temp_sales_checkin_risk_review_member em
                      ON em.tenant_id=e.tenant_id AND em.review_id=e.id AND em.member_key=m.member_key
                    GROUP BY m.group_kind,m.group_id,e.id,e.rules_version,e.member_count,e.status
                ), risk_submission_reviews AS (
                    SELECT m.submission_id,MAX(g.review_status='PENDING') AS pending,
                           MAX(g.review_status='EXPLAINED') AS explained,MAX(g.review_status='FLAGGED') AS flagged,
                           MAX(g.review_status='INCONCLUSIVE') AS inconclusive
                    FROM risk_group_members m JOIN risk_group_states g
                      ON g.group_kind=m.group_kind AND g.group_id=m.group_id GROUP BY m.submission_id
                )
                """;
    }

    static String levelExpression() {
        return "CASE WHEN s.risk_level='HIGH' OR rf.device_salesperson_count>1 OR rf.audio_cross_sales=1 OR rf.audio_cross_date=1 THEN 'HIGH' "
                + "WHEN s.risk_level='MEDIUM' OR rf.audio_duplicate_count>0 THEN 'MEDIUM' "
                + "WHEN s.risk_level='LOW' THEN 'LOW' ELSE 'NONE' END";
    }

    static void appendFilters(StringBuilder sql, List<Object> args, TemporaryCheckinRepository.AdminReadOptions options) {
        if (!options.needsRisk()) return;
        if (options.hasRiskFilters()) sql.append(" AND s.status='SUBMITTED' AND s.deletion_state='NONE'");
        if (options.riskLevel()!=null) { sql.append(" AND ("+levelExpression()+")=?");args.add(options.riskLevel()); }
        if ("SHARED".equals(options.deviceRisk())) sql.append(" AND rf.device_salesperson_count>1");
        if ("DUPLICATE".equals(options.audioRisk())) sql.append(" AND rf.audio_duplicate_count>0");
        if ("CROSS_SALES".equals(options.audioRisk())) sql.append(" AND rf.audio_cross_sales=1");
        if (!options.riskFlags().isEmpty()) {
            List<String> predicates=new ArrayList<>();
            for(String flag:options.riskFlags()) {
                switch(flag) {
                    case "SHARED_DEVICE" -> predicates.add("rf.device_salesperson_count>1");
                    case "AUDIO_DUPLICATE" -> predicates.add("rf.audio_duplicate_count>0");
                    case "AUDIO_CROSS_SALES" -> predicates.add("rf.audio_cross_sales=1");
                    case "AUDIO_CROSS_DATE" -> predicates.add("rf.audio_cross_date=1");
                    default -> {predicates.add("JSON_CONTAINS(s.risk_flags_json,JSON_QUOTE(?))");args.add(flag);}
                }
            }
            sql.append(" AND (").append(String.join(" OR ",predicates)).append(')');
        }
        if(options.riskQuery()!=null) {
            sql.append(" AND (rf.device_code LIKE ? OR rf.audio_codes LIKE ?)");
            args.add("%"+options.riskQuery()+"%");args.add("%"+options.riskQuery()+"%");
        }
        if(options.riskReviewStatus()!=null) {
            String column=switch(options.riskReviewStatus()) {
                case "EXPLAINED" -> "explained";case "FLAGGED" -> "flagged";case "INCONCLUSIVE" -> "inconclusive";default -> "pending";
            };
            sql.append(" AND rr.").append(column).append("=1");
        }
    }
}
