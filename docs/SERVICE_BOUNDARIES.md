# 服务边界

| 应用 | 端口 | 数据主权与职责 |
|---|---:|---|
| rigour-api-gateway | 26880 | 路由、JWT资源服务器、IAM当前Token在线确认和HMAC签名的身份/角色/权限上下文入口；限流及事件投影待实现 |
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

每个领域服务独占自己的Schema、数据库账号和写权限。IAM逻辑Schema为`rigour_iam`：共享DEV当前执行V1～V6，功能分支V7增加OIDC、V8增加管理导航与租户设置，两者尚未应用共享DEV。IAM运行账号和迁移账号必须分离，密码通过环境Secret注入而不是写入Nacos。

服务之间无Maven实现依赖，通过API、事件或本地投影协作。每个领域服务的Java调用契约位于同一聚合工程的`<domain>-api`，调用方只允许依赖API模块。所有外部业务请求必须经过Gateway；服务只信任签名且未过期的调用人上下文，不能信任浏览器或普通内部调用方自填的`X-Rigour-*`。BI聚合由analytics-bi-service提供，不在页面或Gateway中实时拼装多个业务库。
