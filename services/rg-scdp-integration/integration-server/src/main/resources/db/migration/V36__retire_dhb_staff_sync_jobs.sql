-- Release slots held by retired jobs and retain their history.
UPDATE integration_dhb_page_job
SET status = 'FAILED',
    stage = '员工和归属地区同步已停用，请在系统内维护',
    active_slot = NULL,
    finished_at = COALESCE(finished_at, UTC_TIMESTAMP(6))
WHERE scope IN ('SALESPERSON', 'AREA')
  AND status IN ('QUEUED', 'RUNNING', 'UNKNOWN');
