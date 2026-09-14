-- V75 使用新 ID ...376 插入已由 ...240 占用的 BI_SALES 唯一编码，
-- ON DUPLICATE KEY 只更新旧行，随后 UI 引用不存在的新 ID，导致空库回放失败。
-- 保留 V75 原文件：暂让出唯一编码，V75.1 随即合并回原 ID，供 V76 接续。
-- 已完成 V75 的环境中两步只是可逆的编码过渡，不更改最终导航和授权。
UPDATE iam_resource original
LEFT JOIN iam_resource duplicate ON duplicate.id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000376')
SET original.resource_code='SUPPLY_CHAIN.PAGE.BI_SALES_PRE_V75'
WHERE original.id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000240')
  AND original.resource_code='SUPPLY_CHAIN.PAGE.BI_SALES'
  AND duplicate.id IS NULL;
