package com.rigour.sales.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.sales.api.v1.model.SalesPayrollVisitView;
import com.rigour.sales.infrastructure.persistence.entity.SalesPayrollSubmissionEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SalesPayrollSourceMapper extends BaseMapper<SalesPayrollSubmissionEntity> {
    @Select(
            "SELECT COUNT(*) FROM temp_sales_checkin_employee_link WHERE"
                    + " tenant_id=UUID_TO_BIN(#{tenant}) AND employee_code=#{employee}")
    int linked(@Param("tenant") String tenant, @Param("employee") String employee);

    // 只读取与当前录音摘要匹配的服务端派生时长。
    @Select(
            """
SELECT BIN_TO_UUID(s.id) sourceId,DATE(CONVERT_TZ(s.submitted_at,'+00:00','+08:00')) `date`,
 COALESCE(CAST(c.customer_id AS CHAR),CONCAT('STORE:',BIN_TO_UUID(s.store_id))) customerKey,s.review_status reviewStatus,
 (SELECT SUM(d.duration_ms) FROM temp_sales_checkin_risk_audio_source a
  LEFT JOIN temp_sales_checkin_media_derivative d ON d.tenant_id=a.tenant_id AND d.submission_id=a.submission_id
    AND d.media_id=a.segment_id AND LOWER(d.source_sha256)=a.sha256 AND d.kind='AUDIO' AND d.status='READY'
  WHERE a.tenant_id=s.tenant_id AND a.submission_id=s.id) verifiedCommunicationMillis,
 EXISTS(SELECT 1 FROM temp_sales_checkin_risk_audio_source a WHERE a.tenant_id=s.tenant_id AND a.submission_id=s.id)
 AND NOT EXISTS(SELECT 1 FROM temp_sales_checkin_risk_audio_source a
   LEFT JOIN temp_sales_checkin_media_derivative d ON d.tenant_id=a.tenant_id AND d.submission_id=a.submission_id
    AND d.media_id=a.segment_id AND LOWER(d.source_sha256)=a.sha256 AND d.kind='AUDIO' AND d.status='READY'
   WHERE a.tenant_id=s.tenant_id AND a.submission_id=s.id AND (d.duration_ms IS NULL OR d.duration_ms<0)) durationComplete, s.submitted_at submittedAt
FROM temp_sales_checkin_submission s
JOIN temp_sales_checkin_employee_link e ON e.tenant_id=s.tenant_id AND e.salesperson_id=s.salesperson_id
LEFT JOIN temp_sales_checkin_customer_link c ON c.tenant_id=s.tenant_id AND c.store_id=s.store_id
WHERE s.tenant_id=UUID_TO_BIN(#{tenant}) AND e.employee_code=#{employee} AND s.status='SUBMITTED' AND s.deletion_state='NONE'
  AND s.submitted_at>=#{from} AND s.submitted_at<#{until} ORDER BY s.submitted_at,s.id LIMIT 10001
""")
    List<SalesPayrollVisitView> visits(
            @Param("tenant") String tenant,
            @Param("employee") String employee,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);
}
