CREATE TABLE integration_huolala_voucher (
 id CHAR(64) NOT NULL COMMENT '企业连接、平台订单及费用凭证身份摘要', tenant_id VARCHAR(36) NOT NULL COMMENT '租户',
 connector_id VARCHAR(36) NOT NULL COMMENT '来源连接', enterprise_id VARCHAR(80) NOT NULL COMMENT '来源企业',
 order_no VARCHAR(80) NOT NULL COMMENT '货拉拉单号', line_id VARCHAR(50) NOT NULL COMMENT '源费用行',
 source_url TEXT NOT NULL COMMENT '平台图片地址，仅服务端使用', file_name VARCHAR(255) NOT NULL COMMENT '文件名',
 file_key VARCHAR(500) NULL COMMENT '自有私有COS对象', content_type VARCHAR(100) NULL COMMENT '经校验文件类型',
 state VARCHAR(20) NOT NULL COMMENT 'PENDING待获取READY待投递WORKING处理中DONE完成FAILED待重试',
 attempts INT NOT NULL DEFAULT 0 COMMENT '重试次数', next_attempt DATETIME(6) NOT NULL COMMENT '下次处理时间',
 lease_token VARCHAR(36) NULL COMMENT '领取令牌', lease_until DATETIME(6) NULL COMMENT '领取到期时间',
 saved_at DATETIME(6) NULL COMMENT 'COS保存时间', last_error VARCHAR(100) NULL COMMENT '非敏感错误类型',
 PRIMARY KEY(tenant_id,id), KEY ix_huolala_voucher_pending(state,next_attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='货拉拉凭证自动留存与投递任务';
