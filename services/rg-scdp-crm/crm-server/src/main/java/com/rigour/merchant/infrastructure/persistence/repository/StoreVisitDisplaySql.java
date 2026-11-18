package com.rigour.merchant.infrastructure.persistence.repository;

/** Shared display state; effective-visit accounting always uses the persisted business status. */
final class StoreVisitDisplaySql {
    private StoreVisitDisplaySql() {}
    static final String STATE="""
        CASE WHEN v.review_source='CANCELLED' THEN 'CANCELLED'
        WHEN v.checked_out_at IS NOT NULL AND v.submitted_at IS NULL AND v.status_code='IN_PROGRESS' THEN 'PENDING_SUBMIT'
        WHEN EXISTS(SELECT 1 FROM crm_visit_appeal a WHERE a.tenant_id=v.tenant_id AND a.visit_id=v.id AND a.status='PENDING') THEN 'APPEALED'
        WHEN v.verification_state='INSUFFICIENT' THEN 'INSUFFICIENT'
        WHEN v.status_code='SUBMITTED' THEN v.verification_state ELSE v.status_code END
        """;
}
