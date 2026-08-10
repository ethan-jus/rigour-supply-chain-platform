# Sales Work Service

Sales Work 是飞书销售 H5 和 Portal 销售管理的业务后端，Schema 为 `rigour_sales_work`。

## 数据主权

- 主写：销售画像、团队、外勤规则、H5 签到/签退、销售工作日、定位、拜访、录音业务关系、复核和销售日结候选。
- 只读引用/投影：HR 员工任职、CRM 客户门店和销售归属。
- 不主写：CRM 门店、HR 正式考勤、薪酬、COS 二进制对象和 AI 模型结果原文。

## 当前落地

- `sales-work-api` 保留版本化跨服务契约边界；
- `sales-work-service` 已接入 JDBC、MyBatis-Plus、Flyway、幂等、Outbox 和审计落库；
- Flyway V1 建立规则、销售组织投影、打卡、位置、拜访、录音、AI 结果快照、复核、幂等、Outbox 和审计表；V2 增加 IAM 到 Sales Work 的身份投影；V3 增加拜访结果字段；V4 增加录音片段客户端幂等标识；V5修复启用录音规则的0秒异常；V6增加不保存音频的短录音审计事实；
- Gateway 已将 `/api/v1/sales/**` 路由到本服务；
- 阶段 1 已实现本人销售上下文、当前外勤规则和 CRM 门店目标的租户/权限隔离查询；
- 已实现签到/重新签到、应用级定位批量上报、中断证据、签退、本人工作日与当日轨迹查询，以及同库事务内的幂等、Outbox 和审计事实；日结只生成 `PENDING_REVIEW` 候选，不写 HR 正式考勤；
- 已实现我的门店/高德 POI 双来源拜访、到店/离店位置校验、单一进行中拜访、必填拜访结果、规则化录音时长门禁、10分钟自动分片后的幂等上传与会话查询；30秒以下音频不入对象存储，只登记幂等短录音审计；开发可落本地文件系统，生产可切换腾讯云 COS；
- IAM V32 为已经具备 H5 拜访写权限的普通销售角色补齐本人轨迹与录音读写权限；部署后需重新进入飞书工作台获取新会话。
- AI 音频真实性/时长验证、ASR、主管复核管理端、HR/BI 事件消费者和 KPI 仍是独立后续能力；客户端上报时长只用于完成流程门禁，不写入 `verified_total_duration_ms`。

## 运行边界

开发环境数据源由 Nacos `rigour-sales-work-service.yaml` 与进程 Secret 共同提供。COS 的 `storage-type`、`region`、`bucket`、大小限制、超时和服务端加密开关属于非敏感配置，保存在 application/Nacos；只有 `SecretId`、`SecretKey` 和临时凭据的 `SessionToken` 从部署 Secret 注入。真实密码、COS 凭据和高德 Web Key 不进入 Git、Nacos 或前端。完整配置和腾讯云创建步骤见 [`docs/COS_RECORDING_SETUP.md`](../../docs/COS_RECORDING_SETUP.md)。上下文测试使用轻量测试配置；Sales Work 迁移和纵向闭环使用隔离 MySQL 8.4 Testcontainers 验证，不替代共享 DEV、真实飞书、COS 或跨服务运行时验收。
