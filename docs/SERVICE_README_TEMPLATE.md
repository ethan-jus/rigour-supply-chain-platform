# `<服务名称>`

> 本 README 只记录服务当前事实；平台级协同规则见 [`TEAM_DEVELOPMENT_GUIDE.md`](TEAM_DEVELOPMENT_GUIDE.md)，完整服务边界见 [`SERVICE_BOUNDARIES.md`](SERVICE_BOUNDARIES.md)。

## 1. 服务卡片

| 项目 | 内容 |
|---|---|
| 服务名 | `<spring.application.name>` |
| 端口 | `<port>` |
| 代码目录 | `<path>` |
| Schema | `<schema>` |
| 数据主写者 | `<service>` |
| 负责人 | `<owner/team>` |
| 当前状态 | `<骨架/开发中/可联调/已验收>` |

## 2. 负责什么

- `<主责 1>`
- `<主责 2>`

## 3. 不负责什么

- `<禁止复制的外部能力或业务能力>`
- `<禁止直接访问的服务、Schema 或 Secret>`

## 4. API 与事件

| 类型 | 契约 | 用途 | 调用方/消费者 |
|---|---|---|---|
| HTTP | `<versioned path or API module>` | `<purpose>` | `<caller>` |
| Event | `<schemaVersion/eventName>` | `<purpose>` | `<consumer>` |

契约细节链接：`<docs or API module>`。

## 5. 数据与 Secret

- Schema 和 Flyway 所有者：`<service>`。
- 主表：`<tables>`。
- 只读投影：`<tables or none>`。
- 幂等键：`<key>`。
- Secret 只保存 `<secret ref>`，解析位置为 `<service/adapter>`；不得出现在请求、日志和数据库明文中。

## 6. 依赖与调用方向

```text
调用方 -> 本服务版本化 API/事件 -> 本服务
```

- 允许依赖：`<API modules / shared modules>`。
- 禁止依赖：其他服务的 `*-service` 实现、跨 Schema SQL、浏览器自填身份头。

## 7. 启动与验证

```bash
<copyable command>
```

- 健康检查：`<url>`
- 单元/集成测试：`<command>`
- 真实 DEV 验收：`<document>`

## 8. 日志与排障

必须包含：`requestId`、`correlationId`、`tenantId`、`principalId`、`<domain identifiers>`。
禁止记录：密码、Token、API Key、Cookie、完整请求体和不必要的个人信息。

## 9. 修改前检查

- [ ] 已在三个仓库搜索同类实现和旧入口。
- [ ] 已确认本服务是唯一 owner。
- [ ] API/事件、权限、tenant 隔离和幂等已评审。
- [ ] DB 迁移和账号权限由本服务 owner 管理。
- [ ] 删除了被替代的代码、配置、测试和文档引用。
- [ ] 更新了本 README 和相关架构文档。
