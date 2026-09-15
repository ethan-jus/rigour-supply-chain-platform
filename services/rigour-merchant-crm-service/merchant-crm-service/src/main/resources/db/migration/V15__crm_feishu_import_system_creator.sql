-- 旧版飞书投影曾把服务 principal UUID 写入创建人；现行服务调用已统一写 SYSTEM。
-- UUID 来自 nameUUIDFromBytes("rigour-integration-feishu-import-service")，不是租户或人员编号。
-- 仅修正飞书来源且创建人为该服务的记录；保留人工创建人、创建时间和后续更新审计。
-- crm_customer.updated_time 带 ON UPDATE，必须显式原值赋回，避免本次修正篡改更新时间。

UPDATE crm_customer
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE source_system_code = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_party
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_customer_area
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_customer_type
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_contact
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_address
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_party_role r
   SET r.created_by = 'SYSTEM', r.updated_time = r.updated_time
 WHERE r.created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab'
   AND EXISTS (SELECT 1 FROM crm_party p
                WHERE p.tenant_id = r.tenant_id AND p.id = r.party_id AND p.record_origin = 'FEISHU');

UPDATE crm_customer_profile c
   SET c.created_by = 'SYSTEM', c.updated_time = c.updated_time
 WHERE c.created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab'
   AND EXISTS (SELECT 1 FROM crm_party p
                WHERE p.tenant_id = c.tenant_id AND p.id = c.party_id AND p.record_origin = 'FEISHU');
