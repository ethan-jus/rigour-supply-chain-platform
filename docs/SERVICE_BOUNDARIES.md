# 服务边界

| 应用 | 端口 | 数据主权与职责 |
|---|---:|---|
| rigour-api-gateway | 26880 | 路由和调用链入口；当前未实现生产级鉴权/限流 |
| rigour-tenant-iam-service | 26881 | 租户、组织、身份、角色、许可和 DataScope Policy |
| rigour-integration-migration-service | 26882 | 外部连接器、Raw Landing、同步、映射、核对和主权状态 |
| rigour-merchant-crm-service | 26883 | 商家、品牌、门店、销售归属、信用和结算政策 |
| rigour-erp-core-service | 26884 | SKU、仓库、库存、供应商、采购、应付和成本 |
| rigour-order-center-service | 26885 | 订单、履约、售后、应收、回款核销、发票和对账 |
| rigour-sales-work-service | 26886 | 日历、考勤、定位、拜访、录音证据、审批快照和日结 |
| rigour-ai-agent-service | 26887 | ASR、业务初审、查重、知识问答、战报 Agent 和模型治理 |
| rigour-analytics-bi-service | 26888 | 指标、分析分层、驾驶舱、排名、战报和锁定快照 |
| rigour-hr-payroll-service | 26889 | 任职、工资、绩效、提成、月结和冲回 |
| rigour-city-operations-service | 26890 | 城市成本、预算、活动、合作方、营销、复盘和培训 |
| rigour-channel-agent-service | 26891 | 代理等级、关系树、额度、审批、占用和释放流水 |

每个领域服务独占自己的 Schema、数据库账号和写权限。当前脚手架尚未声明数据库驱动或 Schema，这条规则需要后续 Flyway、账号配置和部署验收共同落实。

服务之间无 Maven 实现依赖，通过 API、事件或本地投影协作。BI 聚合由 analytics-bi-service 提供，不在页面或 Gateway 中实时拼装多个业务库。
