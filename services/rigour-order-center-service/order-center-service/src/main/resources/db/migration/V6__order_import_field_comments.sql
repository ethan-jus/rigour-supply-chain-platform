-- 为既有订单导入表补齐列级说明；不修改V2历史迁移，避免已执行Flyway校验和漂移。
-- 状态注释明确区分订货宝来源状态与平台internal_status。
-- 先临时移除三条外键，避免MySQL拒绝MODIFY被外键引用的列；迁移末尾按原规则恢复。

ALTER TABLE order_order_line DROP FOREIGN KEY fk_order_line_order;
ALTER TABLE order_order_shipment DROP FOREIGN KEY fk_order_shipment_order;
ALTER TABLE order_source_record DROP FOREIGN KEY fk_order_source_record_order;

ALTER TABLE order_order
    MODIFY COLUMN id CHAR(36) NOT NULL COMMENT '平台内部订单ID，UUID',
    MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL COMMENT '租户ID，来自可信签名上下文；所有查询和写入必须隔离',
    MODIFY COLUMN order_no VARCHAR(80) NOT NULL COMMENT '平台订单号；一期订货宝导入默认等于source_order_no',
    MODIFY COLUMN source_system VARCHAR(32) NOT NULL COMMENT '来源系统编码；订货宝数据固定DINGHUOBAO',
    MODIFY COLUMN source_order_no VARCHAR(80) NOT NULL COMMENT '订货宝订单号OrderSN；与tenant_id、source_system组成幂等键',
    MODIFY COLUMN internal_status VARCHAR(40) NOT NULL COMMENT '平台内部状态：RECEIVED、PENDING_CONFIRMATION、ALLOCATING、SHIPPED、COMPLETED、CANCELLED、EXCEPTION；外部同步不得覆盖已有值',
    MODIFY COLUMN source_status VARCHAR(40) NULL COMMENT '订货宝OrderStatus响应原值：pricing待核价、pending待审核、stockup待出库、shipped待发货、received待收货、finished已完成、forcedone强制完成、cancelled已取消；stock_up为查询参数/历史兼容值',
    MODIFY COLUMN payment_status VARCHAR(40) NULL COMMENT 'getOrderList.PayStatus收款状态：oblig待收款、uncollect部分收款、paided已收款、cancelled已取消、wait待确认、part部分确认',
    MODIFY COLUMN order_type VARCHAR(40) NULL COMMENT '订货宝订单类型OrderType原值',
    MODIFY COLUMN total_amount DECIMAL(18,4) NULL COMMENT '订货宝订单总金额OrderTotal，最多4位小数',
    MODIFY COLUMN ordered_at DATETIME(6) NULL COMMENT '订货宝下单时间OrderDate；按Asia/Shanghai解析后统一存UTC',
    MODIFY COLUMN source_updated_at DATETIME(6) NULL COMMENT '订货宝订单更新时间OrderUpdateDate；按Asia/Shanghai解析后统一存UTC',
    MODIFY COLUMN source_update_time VARCHAR(32) NULL COMMENT '订货宝OrderUpdateDate原始文本，供追溯异常时间格式',
    MODIFY COLUMN delivery_date VARCHAR(32) NULL COMMENT '订货宝要求交付日期DeliveryDate/SendDate原始文本',
    MODIFY COLUMN remark VARCHAR(1000) NULL COMMENT '订货宝订单备注OrderRemark/Remark',
    MODIFY COLUMN source_customer_no VARCHAR(80) NULL COMMENT '订货宝客户编号ClientNO/ClientNum',
    MODIFY COLUMN source_customer_guid VARCHAR(80) NULL COMMENT '订货宝客户ERP外码ClientGUID',
    MODIFY COLUMN customer_name VARCHAR(160) NULL COMMENT '订货宝客户名称ClientName/ClientCompanyName快照',
    MODIFY COLUMN receiver_name VARCHAR(80) NULL COMMENT '订货宝收货人OrderReceiveName快照',
    MODIFY COLUMN receiver_company VARCHAR(200) NULL COMMENT '订货宝收货单位OrderReceiveCompany快照',
    MODIFY COLUMN receiver_phone VARCHAR(64) NULL COMMENT '订货宝收货电话OrderReceivePhone；敏感字段，前端按权限脱敏',
    MODIFY COLUMN receiver_address VARCHAR(500) NULL COMMENT '订货宝收货地址OrderReceiveAdd/OrderReceiveAddTwo；敏感字段，前端按权限脱敏',
    MODIFY COLUMN province VARCHAR(80) NULL COMMENT '订货宝收货省份Province',
    MODIFY COLUMN city VARCHAR(80) NULL COMMENT '订货宝收货城市City',
    MODIFY COLUMN district VARCHAR(80) NULL COMMENT '订货宝收货区县District',
    MODIFY COLUMN source_api_status VARCHAR(8) NULL COMMENT '订货宝下载标记OrderApi/ApiStatus：F未下载、T已下载',
    MODIFY COLUMN source_exception_status VARCHAR(8) NULL COMMENT '订货宝异常标记ExceptionStatus：F正常、T异常',
    MODIFY COLUMN source_send_type VARCHAR(80) NULL COMMENT '订货宝发货方式SendType原值',
    MODIFY COLUMN source_last_order_at VARCHAR(32) NULL COMMENT '订货宝最后下单时间LastOrderDate原始文本',
    MODIFY COLUMN source_device VARCHAR(40) NULL COMMENT '订货宝下单设备SourceDevice原值',
    MODIFY COLUMN source_admin_order VARCHAR(8) NULL COMMENT '订货宝管理员订单标记IsAdminOrder原值',
    MODIFY COLUMN split_type VARCHAR(32) NULL COMMENT '订货宝拆单类型SplitType原值',
    MODIFY COLUMN split_type_name VARCHAR(80) NULL COMMENT '订货宝拆单类型中文名SplitTypeName',
    MODIFY COLUMN source_payload_hash CHAR(64) NOT NULL COMMENT '当前有效列表或详情Raw JSON的SHA-256小写十六进制摘要',
    MODIFY COLUMN detail_synced_at DATETIME(6) NULL COMMENT '最近成功保存getOrderContent详情的时间，UTC；为空表示只有列表摘要',
    MODIFY COLUMN imported_at DATETIME(6) NOT NULL COMMENT '订单首次导入订单中心的时间，UTC',
    MODIFY COLUMN synced_at DATETIME(6) NOT NULL COMMENT '订单最近一次成功同步落库时间，UTC',
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '本地记录版本号；来源内容变化或详情刷新时递增',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '本地记录创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '本地记录最后更新时间，UTC';

ALTER TABLE order_order_line
    MODIFY COLUMN id CHAR(36) NOT NULL COMMENT '平台订单明细ID，UUID',
    MODIFY COLUMN order_id CHAR(36) NOT NULL COMMENT '所属order_order.id',
    MODIFY COLUMN source_line_id VARCHAR(100) NOT NULL COMMENT '订货宝订单明细ID orders_list_id；来源缺失时由Integration生成稳定键',
    MODIFY COLUMN source_product_guid VARCHAR(100) NULL COMMENT '订货宝商品ERP外码guid/Guid/TrueGuid',
    MODIFY COLUMN sku_no VARCHAR(100) NULL COMMENT '订货宝规格商品编码options_goods_num/skuNo',
    MODIFY COLUMN source_options_goods_no VARCHAR(100) NULL COMMENT '订货宝商品选项编号OptionsGoodsNo；来源未返回时为空',
    MODIFY COLUMN source_barcode VARCHAR(160) NULL COMMENT '订货宝规格条码options_barcode',
    MODIFY COLUMN product_name VARCHAR(200) NULL COMMENT '订货宝商品名称Name快照',
    MODIFY COLUMN product_code VARCHAR(100) NULL COMMENT '订货宝商品编码Coding',
    MODIFY COLUMN specification_first VARCHAR(100) NULL COMMENT '订货宝第一层规格multiFirst',
    MODIFY COLUMN specification_second VARCHAR(100) NULL COMMENT '订货宝第二层规格multiSecond',
    MODIFY COLUMN specification_name VARCHAR(200) NULL COMMENT '订货宝组合规格名称multiName',
    MODIFY COLUMN unit_price DECIMAL(18,4) NULL COMMENT '订货宝订单明细单价ContentPrice/order_units_price',
    MODIFY COLUMN quantity DECIMAL(18,4) NULL COMMENT '订货宝订单明细数量ContentNumber/order_units_number',
    MODIFY COLUMN line_amount DECIMAL(18,4) NULL COMMENT '订货宝订单明细实际金额ActualAmount',
    MODIFY COLUMN unit VARCHAR(40) NULL COMMENT '订货宝订单单位order_units_name/base_units_name/Units',
    MODIFY COLUMN remark VARCHAR(1000) NULL COMMENT '订货宝订单明细备注remark',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '本地明细创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '本地明细最后更新时间，UTC';

ALTER TABLE order_order_shipment
    MODIFY COLUMN id CHAR(36) NOT NULL COMMENT '平台订单内发货快照ID，UUID',
    MODIFY COLUMN order_id CHAR(36) NOT NULL COMMENT '所属order_order.id',
    MODIFY COLUMN source_shipment_no VARCHAR(100) NOT NULL COMMENT 'getOrderContent.Ships发货单号ships_num；与order_id组成幂等键',
    MODIFY COLUMN status VARCHAR(40) NULL COMMENT 'Ships.status来源状态原值：shipped待发货、receivedin待收货、received已收货、cancelled已取消',
    MODIFY COLUMN shipment_date VARCHAR(32) NULL COMMENT 'Ships.ships_date来源发货时间原始文本',
    MODIFY COLUMN stock_up_time VARCHAR(32) NULL COMMENT 'Ships.stock_up_time来源备货时间原始文本',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '本地快照创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '本地快照最后更新时间，UTC';

ALTER TABLE order_source_record
    MODIFY COLUMN id CHAR(36) NOT NULL COMMENT '不可变来源报文记录ID，UUID',
    MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL COMMENT '租户ID，来自可信签名上下文',
    MODIFY COLUMN order_id CHAR(36) NOT NULL COMMENT '关联order_order.id',
    MODIFY COLUMN source_system VARCHAR(32) NOT NULL COMMENT '来源系统编码；订货宝数据固定DINGHUOBAO',
    MODIFY COLUMN source_order_no VARCHAR(80) NOT NULL COMMENT '订货宝订单号OrderSN',
    MODIFY COLUMN payload_type VARCHAR(16) NOT NULL COMMENT '报文类型：LIST=getOrderList单条摘要、DETAIL=getOrderContent完整详情',
    MODIFY COLUMN payload_json JSON NOT NULL COMMENT '单条订货宝原始JSON，不含sKey、SerialNumber或Password；用于审计和重放',
    MODIFY COLUMN payload_hash CHAR(64) NOT NULL COMMENT 'payload_json的SHA-256小写十六进制摘要，用于幂等去重',
    MODIFY COLUMN received_at DATETIME(6) NOT NULL COMMENT '订单中心接收该来源报文的时间，UTC';

ALTER TABLE order_order_line
    ADD CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES order_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE order_order_shipment
    ADD CONSTRAINT fk_order_shipment_order FOREIGN KEY (order_id) REFERENCES order_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE order_source_record
    ADD CONSTRAINT fk_order_source_record_order FOREIGN KEY (order_id) REFERENCES order_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT;
