# 门店地区维护与高德候选补全

本轮补齐商家库“完善门店资料”的地区、经纬度和坐标系维护。公司门店 ID 不变，不关联或迁移旧客户档案。

## 操作与数据

- 所属地区读取管理权限下的行政区划目录，虚拟列表展示完整省 / 市 / 区县路径，支持按任一级名称搜索。无县级建制城市可选合法城市叶子节点。
- “从高德补全”按表单名称和地区编码查询，最多展示 20 个候选。必须确认后填入，支持撤销；空地址、电话或无法映射的分类不覆盖人工资料。
- CRM 通过签名内部接口复用 Sales 的高德 Web 服务配置。关键词检索使用 city=adcode、citylimit=true、extensions=all；保存新 POI 关联前再次调用详情核验地区及坐标，查询失败仍可手动维护。
- 类型映射到既有 CRM_STORE_TYPE 字典的 POI 分类编码，不新建平行分类表。公开电话不是联系人身份。
- 更新需 crm:store:manage，校验原门店和目标地区权限、版本号及请求 ID。主档与统计投影在同一事务中更新；省、市、县、七大区域从行政区划目录推导。
- 相同租户的 AMAP/CN/POI 唯一关联，重复关联拒绝且整笔回滚。更换时旧关联标记 INACTIVE，保留来源记录，新关联记录 API_CONFIRM。修改前后地区、坐标、POI 和基础资料均留痕；负责人和归属历史不修改。
- 新增接口：GET /api/v1/crm/stores/{id}/poi-candidates；PUT 原资料接口增加可选 location、amapPoiId。旧调用不传 location 时保留位置。无需数据库迁移。

## 验证

- CRM：StoreMaintenanceServiceTest 4、StoreMapServiceTest 6、JdbcStorePoolStoreTest 21，全部通过；持久化测试使用独立 MySQL 8.4 Testcontainer。
- Sales：StorePoiControllerTest 3、RestAmapPoiClientTest 7，全部通过，覆盖签名服务身份权限边界、地区限定、扩展字段和详情 ID 校验。
- 前端：crm-store-editor 4、crm-store-interactions 27、crm-store 7、crm-store-classification 4，全部通过；vue-tsc、相关 ESLint、git diff --check 通过。
- 真实 Chrome：管理员商家库打开维护弹窗，读取全国目录，原门店省市县完整回显，输入“兰州”显示 8 个所属区县选项，未卡顿。取消编辑，没有写入真实门店。
- 用户已重启现有 IDEA CRM(26883)、Sales(26886)。真实关键词检索和 POI 详情均返回 INVALID_USER_IP（10005）；签名服务调用到达 Sales，失败原因是高德 Web 服务 Key 的 IP 白名单。用户选择先完成手动维护，稍后配置白名单；在线补全成功验收仍待完成。

## 2026-11-03 手动维护真实联调

- 使用现有 DEV 虚拟门店“【DEV私海作业1030】杭州验证门店”，通过 Chrome 商家库编辑界面保存，不通过 SQL 写业务数据，也未关联历史客户档案。
- 第一次保存：杭州上城区改为甘肃 / 兰州 / 城关区，填写测试地址、GCJ-02 坐标 103.8343 / 36.0611、虚拟电话 000-00000000，并从三级字典选择台球厅。主档 revision 1 → 2；统计投影同步为 NORTHWEST / 620000 / 620100 / 620102。西北筛选返回该门店。
- 第二次保存：恢复浙江 / 杭州 / 上城区和原坐标 120.199425 / 30.288925，保留 DEV 地址、台球厅分类和虚拟电话；revision 2 → 3，投影恢复 EAST / 330000 / 330100 / 330102。西北筛选不再返回该门店。
- 数据库核对：两条 PROFILE_UPDATED 记录完整保留修改前后地区、地址、分类、电话、坐标和修改原因。页面修改人显示“管理员”。公海状态、空负责人和原有一条归属历史均保持；无新增外部身份关联。
- Chrome 整页刷新后重新检索并打开详情，分类、杭州省市县、地址、坐标、电话及修改人时间均正确回显。截图保存在工作区 `outputs/crm-location-maintenance-20261103/manual-save-verified.png`。
- 本次新增高德白名单错误的安全原因码传递：Sales → CRM → 编辑弹窗仅识别 AMAP_IP_NOT_ALLOWED，显示 10005 / IP 白名单提示；未知上游错误仍不透传内部诊断。此错误提示后端代码已编译，随下次 CRM / Sales 重启加载，本次未为该提示单独重启服务。
- 本次定向回归：CRM 7 项、Sales 11 项、前端 32 项全部通过；前端 typecheck、相关 ESLint 和两仓库 git diff --check 通过。

官方字段依据：https://lbs.amap.com/api/webservice/guide/api-advanced/search
