# Sales Work Service

Sales Work 是飞书销售 H5 和 Portal 销售管理的业务后端，Schema 为 `rigour_sales_work`。

## 数据主权

- 主写：销售画像、团队、外勤规则、H5 签到/签退、销售工作日、定位、拜访、录音业务关系、复核和销售日结候选。
- 只读引用/投影：HR 员工任职、CRM 客户门店和销售归属。
- 不主写：CRM 门店、HR 正式考勤、薪酬、COS 二进制对象和 AI 模型结果原文。

## 当前落地

- `sales-work-api` 保留版本化跨服务契约边界；
- `sales-work-service` 已接入 JDBC、MyBatis-Plus、Flyway、幂等、Outbox 和审计落库；
- Flyway V1 建立规则、销售组织投影、打卡、位置、拜访、录音、AI 结果快照、复核、幂等、Outbox 和审计表；V2 增加 IAM 到 Sales Work 的身份投影；
- Gateway 已将 `/api/v1/sales/**` 路由到本服务；
- 阶段 1 已实现本人销售上下文、当前外勤规则和 CRM 门店目标的租户/权限隔离查询；
- 阶段 2 已实现签到、定位批量上报、中断证据、签退、本人工作日查询，以及同库事务内的幂等、Outbox 和审计事实；日结只生成 `PENDING_REVIEW` 候选，不写 HR 正式考勤；
- 拜访、录音/COS、AI、复核管理端、HR/BI 事件消费者和 KPI 仍属于后续阶段，尚未实现。

## 运行边界

开发环境数据源由 Nacos `rigour-sales-work-service.yaml` 与进程 Secret 共同提供。真实密码不进入 Git、Nacos或前端。上下文测试使用轻量测试配置；Sales Work 迁移和阶段 2 纵向闭环使用隔离 MySQL 8.4 Testcontainers 验证，不替代共享 DEV、真实飞书或跨服务运行时验收。
