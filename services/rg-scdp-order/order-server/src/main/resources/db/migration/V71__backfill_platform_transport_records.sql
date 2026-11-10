-- 已执行V70保持不变。将历史API来源补入统一列表，保留已有成本及软删除状态。
INSERT INTO order_logistics_cost
 (id,tenant_id,document_no,request_id,category_code,title,cost_date,amount,payment_status_code,status_code,data_json,sources_json,created_by,updated_by,source_system,carrier_code,tracking_no,origin_biz_key)
SELECT i.id,i.tenant_id,CONCAT('LC',DATE_FORMAT(i.created_time,'%Y%m%d'),LEFT(REPLACE(i.id,'-',''),12)),i.id,'LOGISTICS','货拉拉运输',
 COALESCE(STR_TO_DATE(LEFT(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(i.latest_source_json,i.source_json),'$.useTime')),10),'%Y-%m-%d'),DATE(i.created_time)),
 CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(i.latest_source_json,i.source_json),'$.facts.billAmount')),'null') AS DECIMAL(24,6)),
 'UNKNOWN','DRAFT',
 JSON_OBJECT('requestId',i.id,'revision',1,'title','货拉拉运输','costDate',COALESCE(LEFT(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(i.latest_source_json,i.source_json),'$.useTime')),10),DATE_FORMAT(i.created_time,'%Y-%m-%d')),'amount',JSON_EXTRACT(COALESCE(i.latest_source_json,i.source_json),'$.facts.billAmount'),'paymentStatusCode','UNKNOWN','logistics',JSON_OBJECT('sourceIds',JSON_ARRAY(),'voucherIds',JSON_ARRAY(),'carrierName','货拉拉','trackingNo',i.carrier_order_no,'payerCode','COMPANY','entryMode','MANUAL')),
 JSON_ARRAY(),i.created_by,i.created_by,'HUOLALA','HLL',i.carrier_order_no,CONCAT('HUOLALA:',i.enterprise_id,':',i.carrier_order_no)
FROM order_logistics_import_order i
WHERE i.source_channel='API' AND i.cost_id IS NULL
 AND NOT EXISTS(SELECT 1 FROM order_logistics_cost c WHERE c.tenant_id=i.tenant_id AND c.carrier_code='HLL' AND c.tracking_no=i.carrier_order_no);
UPDATE order_logistics_import_order i JOIN order_logistics_cost c ON c.tenant_id=i.tenant_id AND c.id=i.id
 SET i.cost_id=c.id,i.state='GENERATED'
 WHERE i.source_channel='API' AND i.cost_id IS NULL;
UPDATE order_logistics_import_order i JOIN order_logistics_cost c ON c.tenant_id=i.tenant_id AND c.carrier_code='HLL' AND c.tracking_no=i.carrier_order_no
 SET i.cost_id=c.id,i.state='GENERATED'
 WHERE i.source_channel='API' AND i.cost_id IS NULL
 AND (SELECT COUNT(*) FROM order_logistics_cost c2 WHERE c2.tenant_id=i.tenant_id AND c2.carrier_code='HLL' AND c2.tracking_no=i.carrier_order_no)=1;

UPDATE order_logistics_cost c JOIN order_logistics_import_order i ON i.tenant_id=c.tenant_id AND i.cost_id=c.id
 SET c.source_system='HUOLALA' WHERE i.source_channel='API' AND c.source_system<>'HUOLALA';
