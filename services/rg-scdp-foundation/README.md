# Foundation — 公共基础服务

公共基础服务当前承载自研业务字典；文件管理、通知和任务调度尚未作为本次改名新增功能。服务独占 `rigour_settings` Schema；ERP、CRM、Order 和 Portal 只能通过 Gateway/API 使用字典，禁止跨库读取。

## 字典模型

- `data_dictionary`：字典主表，按 `dictionary_code` 唯一定位一本字典。
- `data_dictionary_item`：字典项，按 `dictionary_code + dictionary_item_code` 唯一定位一个选项。
- 表名、字段名统一使用 `lower_snake_case`；字典编码和字典项编码统一使用 `UPPER_SNAKE_CASE`。
- 业务表只保存字典项编码，例如 `PRODUCT_UNIT.BOX`、`PAYMENT_STATUS.PAID`；不保存订货宝状态值。
- 旧的 `biz_dict`、`biz_dict_item` 不再作为主流程表；V10 通过向前迁移删除旧表。

## HTTP API

统一前缀：`/api/v1/business-settings/dictionaries`

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/` | `business-settings:dict:read` | 按 `dictionaryType`、`dictionaryCode` 查询字典 |
| GET | `/{dictionaryId}/items` | `business-settings:dict:read` | 查询一本字典的字典项 |
| GET | `/effective?dictionaryCode={dictionaryCode}` | `business-settings:dict:read` | 查询业务当前可用字典 |
| GET | `/resolve?dictionaryCode={dictionaryCode}` | `business-settings:dict:read` | 查询业务展示字典，兼容历史展示 |
| POST | `/` | `business-settings:dict:write` | 新增字典 |
| PUT | `/{dictionaryId}` | `business-settings:dict:write` | 修改字典，使用 `revision` 做乐观锁 |
| POST | `/{dictionaryId}/items` | `business-settings:dict:write` | 新增字典项 |
| PUT | `/items/{itemId}` | `business-settings:dict:write` | 修改字典项，使用 `revision` 做乐观锁 |

内部接口：`POST /internal/v1/business-settings/dictionaries/items/sync`，仅供领域服务在外部数据导入阶段补齐明确观察到的来源值。它不创建字典定义，不让第三方字段进入业务表；后续订货宝字段映射应优先落在 Integration 映射表。

## 删除与审计约定

- 业务删除统一逻辑删：只更新 `deleted`、`updated_by`、`updated_time`，不物理删除。
- 字典编码、字典项编码创建后不可修改，避免历史业务数据失去解释依据。
- 新增、修改、同步补齐记录 `INFO` 日志，包含 `dictionaryCode`、`dictionaryItemCode`、`revision`、`actorId`、变更数量。
- 列表和解析记录 `DEBUG` 日志，包含查询条件和返回数量。
- 日志不输出数据库密码、信任密钥、请求头或大体量来源报文。

## 数据边界

- Foundation 管自研业务字典：单位、类型、状态、支付方式、标签等。
- ERP、CRM、Order 直接传 `dictionaryCode` 使用字典，不再传旧模型中的 `moduleCode/scope/tenant`。
- 订货宝等外部系统的数据只在 Integration 保留来源记录和映射关系；业务主表使用自研编码和状态流。
- 外部系统删除或下架不会直接物理删除业务数据，只能形成映射状态、同步告警或业务状态变更建议。

## 本地运行

工程为 `rg-scdp-foundation`，启动模块为 `foundation-server`。Java 包名、启动类、运行注册名 `rigour-business-settings-service`、HTTP 路径、权限码及端口 `26892` 保留。

IDEA 重新加载根 Maven 工程后，选择 `foundation-server` 模块和 `dev` Profile 启动。DEV 连接与开发凭据直接读取 `application-dev.yml`，Flyway 复用数据源，在服务启动时迁移；无需另设迁移账号。个人覆盖使用 `local`，生产仍使用独立配置。

已执行迁移不得修改、删除或自动 repair。若迁移校验失败，先确认数据库历史与源码差异，制定针对性恢复方案；不能通过关闭 Flyway、clean 或自动 baseline 绕过。

## 验证

```bash
./mvnw -pl services/rg-scdp-foundation/foundation-server -am test
```

测试覆盖字典创建、修改、字典项维护、父子层级、乐观锁、来源值补齐和客户端降级。
