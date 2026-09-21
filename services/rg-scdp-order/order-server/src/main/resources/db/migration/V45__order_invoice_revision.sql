-- 发票状态流转乐观锁：完成开票 / 撤回申请 / 修改申请都按 status + revision 原子流转。
-- V42、V43 已执行，这里只做增量：不改 V42/V43。
ALTER TABLE order_invoice
    ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本：每次状态或内容变更 +1' AFTER deleted;
