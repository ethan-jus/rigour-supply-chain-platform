-- 保留结果未知时实际等待的 CRM 任务，服务重启后继续核对原任务，不重新提交客户同步。
ALTER TABLE integration_dhb_page_job
    ADD COLUMN dependency_job_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;
