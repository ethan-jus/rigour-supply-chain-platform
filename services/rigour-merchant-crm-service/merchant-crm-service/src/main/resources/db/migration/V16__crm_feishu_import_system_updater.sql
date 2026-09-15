-- 旧飞书导入服务 principal 和客户销售编码修复任务均属于系统操作。
-- 精确匹配已知系统身份，并限定飞书来源，保留后续人工修改的更新人。
-- V15 仅修正创建人，本迁移补齐历史更新人。显式保留 ON UPDATE 时间戳。

UPDATE crm_customer
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE source_system_code = 'FEISHU'
   AND updated_by IN ('f34f98c1-7be2-3bc2-b275-4f8ae8914bab', 'codex-bi-owner-code-repair');

UPDATE crm_party
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_customer_area
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_customer_type
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_contact
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_address
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_party_role r
   SET r.updated_by = 'SYSTEM', r.updated_time = r.updated_time
 WHERE r.updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab'
   AND EXISTS (SELECT 1 FROM crm_party p
                WHERE p.tenant_id = r.tenant_id AND p.id = r.party_id AND p.record_origin = 'FEISHU');

UPDATE crm_customer_profile c
   SET c.updated_by = 'SYSTEM', c.updated_time = c.updated_time
 WHERE c.updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab'
   AND EXISTS (SELECT 1 FROM crm_party p
                WHERE p.tenant_id = c.tenant_id AND p.id = c.party_id AND p.record_origin = 'FEISHU');
