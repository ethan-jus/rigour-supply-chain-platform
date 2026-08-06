# Sales Work Service

Sales Work 是飞书销售 H5 和 Portal 销售管理的业务后端，Schema 为 `rigour_sales_work`。

## 数据主权

- 主写：销售画像、团队、外勤规则、H5 签到/签退、销售工作日、定位、拜访、录音业务关系、复核和销售日结候选。
- 只读引用/投影：HR 员工任职、CRM 客户门店和销售归属。
- 不主写：CRM 门店、HR 正式考勤、薪酬、COS 二进制对象和 AI 模型结果原文。

## 当前落地

- `sales-work-api` 保留版本化跨服务契约边界；
- `sales-work-service` 已接入 JDBC、MyBatis-Plus、Flyway、幂等、Outbox、审计和文件存储端口；
- Flyway V1 建立规则、销售组织投影、打卡、位置、拜访、录音、AI 结果快照、复核、幂等、Outbox 和审计表；
- Gateway 已将 `/api/v1/sales/**` 路由到本服务；
- 具体命令处理、Repository、CRM/HR/AI 事件消费者和 COS 适配器仍需按纵向切片实现。

## 运行边界

开发环境数据源由 Nacos `rigour-sales-work-service.yaml` 与进程 Secret 共同提供。真实密码不进入 Git、Nacos或前端。测试上下文使用 H2 并关闭 Flyway；这只证明 Spring 上下文和编译，不替代 MySQL 8.4 迁移验收。
