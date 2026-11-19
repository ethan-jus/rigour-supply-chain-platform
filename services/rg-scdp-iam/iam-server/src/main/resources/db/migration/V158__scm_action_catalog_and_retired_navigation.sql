-- SCM 菜单和操作目录。只处理明确退役的资源，同一权限交易内迁移原已授予的角色。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);

CREATE TEMPORARY TABLE scm_retired(id BINARY(16) PRIMARY KEY,depth INT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO scm_retired VALUES
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000051'),1),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000052'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000054'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000060'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000062'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000063'),1),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000064'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000068'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000122'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000123'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000124'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000125'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000126'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000129'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000131'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000132'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000133'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000134'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000135'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000136'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000137'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000139'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000140'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000141'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000142'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000143'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000144'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000145'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000147'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000149'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000150'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000151'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000153'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000155'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000156'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000157'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000159'),4),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000160'),4),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000198'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000201'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000203'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000205'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000206'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000207'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000209'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000210'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000211'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000212'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000213'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000215'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000216'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000217'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000218'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000220'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000221'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000222'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000223'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000225'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000226'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000227'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000239'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000241'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000242'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000243'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000244'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000245'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000246'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000247'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000248'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000251'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000252'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000253'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000254'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000255'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000256'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000257'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000258'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000259'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000260'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000261'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000262'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000263'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000264'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000285'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000286'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000297'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000362'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000363'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000364'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000370'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000377'),2),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000380'),3),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000381'),4),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000382'),4),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000398'),3),
(UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752'),3),
(UUID_TO_BIN('608191eb-e47c-5601-86dc-c7e32b92737e'),2),
(UUID_TO_BIN('626dc030-7965-4c93-aa59-395762c78700'),3),
(UUID_TO_BIN('bd7b6e1c-5cb7-4a9d-8d6d-4b0c6b6e1140'),2);

CREATE TEMPORARY TABLE scm_rename(id BINARY(16) PRIMARY KEY,old_name VARCHAR(128),name VARCHAR(128),parent BINARY(16)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO scm_rename VALUES
(UUID_TO_BIN('17c21487-8147-539e-af0a-63250e3b70e5'),'新增员工','新增',NULL),
(UUID_TO_BIN('182eb672-1ee4-58eb-9050-9b108b7e5336'),'查看营销费用成本','查看',NULL),
(UUID_TO_BIN('274dbedd-2d3f-5ab0-92ab-68f7a7f2458d'),'查看运输记录','查看',NULL),
(UUID_TO_BIN('414d844b-19ef-5464-af41-9804bfb9931a'),'导出营销费用成本','自定义导出',NULL),
(UUID_TO_BIN('50b682bb-b401-54db-aecf-ed3899576be8'),'导出进货记录','自定义导出',NULL),
(UUID_TO_BIN('56499e58-8171-55d4-8df1-3bbd8f7ed783'),'查看报销','查看',NULL),
(UUID_TO_BIN('6071de23-128b-5915-a814-a7dd6dd87e81'),'查看损耗记录','查看',NULL),
(UUID_TO_BIN('7220d74a-eead-5cca-9c6d-39a7791023eb'),'导出损耗记录','自定义导出',NULL),
(UUID_TO_BIN('8adc66b8-39b9-516a-aa6f-28f0f907d524'),'导出仓库租赁成本','自定义导出',NULL),
(UUID_TO_BIN('a9aa67f1-c5b7-5f57-bf4b-d794de75b6ac'),'导出运输记录','自定义导出',NULL),
(UUID_TO_BIN('ac8ba67a-8ab4-5390-87b6-ae51148c3b02'),'查看场地费用','查看',NULL),
(UUID_TO_BIN('bc113c0e-38e2-5a2e-9a22-774a20dcf9e3'),'新增部门','新增',NULL),
(UUID_TO_BIN('d20e385a-f794-51d1-9f6d-890df4fad97e'),'查看薪酬规则','查看规则',NULL),
(UUID_TO_BIN('ed76bfc9-c7a9-5e74-920d-f5501102f694'),'查看进货记录','查看',NULL),
(UUID_TO_BIN('f359ce2e-91c2-519e-8ead-1adaba84dd2f'),'查看薪酬','查看',NULL),
(UUID_TO_BIN('6e6f259c-a03d-4bb7-94ac-98ece02a5f83'),'维护门店资料','维护门店',NULL),
(UUID_TO_BIN('719d1406-dc05-4ebc-8507-5122d86012eb'),'认领门店','认领',NULL),
(UUID_TO_BIN('e91aefda-fb71-461e-961b-70b190ed7eac'),'审核门店','审核',NULL),
(UUID_TO_BIN('9fa209d0-a893-4998-831a-617bc0e41cfb'),'查看门店历史','查看历史',NULL),
(UUID_TO_BIN('0170c923-977e-561c-9e6e-be1f66384963'),'维护报销','新增与编辑',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000093'),'查询订货宝同步数据','查看同步记录',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000119'),'查询订单、发货与收退款','查看订单',UUID_TO_BIN('019facf2-0000-7000-8000-000000000055')),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000158'),'查询销售管控台','查看管控台',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000161'),'复核销售拜访','复核拜访',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000162'),'查看精确定位','查看精确轨迹',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000163'),'播放拜访录音','播放录音',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000164'),'查询销售规则','查看规则',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000166'),'发布销售规则','发布',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000271'),'读取销售作业上下文','作业身份',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-00000000028d'),'查询拜访计划','查看计划',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000295'),'查询业务字典','查看',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000360'),'查询供应链BI看板','查看经营看板',UUID_TO_BIN('019facf2-0000-7000-8000-000000000065')),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000384'),'读取HR员工主档','查看',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000386'),'读取HR岗位职位','查看',NULL),
(UUID_TO_BIN('02d2a756-7be6-5ff1-a64f-6c7546affec7'),'新增、编辑及关联运输记录','维护运输记录',NULL),
(UUID_TO_BIN('0d8e5750-e87c-5c0d-b147-965e858146da'),'新增用户','新增',NULL),
(UUID_TO_BIN('28c7b1ae-5bf5-49df-8284-4489c44e2682'),'维护部门客户地域','设置客户地域',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000290'),'查询CRM客户主数据','查看客户',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000282'),'查询ERP商品主数据','查看商品资料',UUID_TO_BIN('019facf2-0000-7000-8000-000000000167')),
(UUID_TO_BIN('3f36e599-d71c-5c8e-af8d-a927fb82937b'),'维护场地费用','新增与编辑',NULL),
(UUID_TO_BIN('433fc760-cc9f-5655-8c7e-c5eb1ad1e534'),'维护营销费用成本','新增与编辑',NULL),
(UUID_TO_BIN('49f0fe8a-8732-4211-b06e-e174ee590a80'),'设置全员应出勤','设置应出勤',NULL),
(UUID_TO_BIN('58083c55-e027-5480-93d6-e8aab7472742'),'维护进货记录','新增与编辑',NULL),
(UUID_TO_BIN('5beb5535-af83-5760-bd27-38211209cab4'),'新增角色','新增',NULL),
(UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500011'),'查看货拉拉接入及员工关联','查看接入信息',NULL),
(UUID_TO_BIN('77810b19-ab90-53ee-b7ff-2cc5c239c3ec'),'新增菜单','新增',NULL),
(UUID_TO_BIN('934ad4e9-36e6-5cf2-9b1d-cf3dd42fc137'),'编辑员工','编辑',NULL),
(UUID_TO_BIN('a86eb5ac-2eac-5ac2-a24d-c95adc82e8cb'),'核算和调整','核算与调整',NULL),
(UUID_TO_BIN('b331fce8-94b8-5a95-b4b1-870f7d9ea052'),'维护损耗记录','新增与编辑',NULL),
(UUID_TO_BIN('cb40e17e-9782-47ec-9c77-a7e54893e782'),'导出成本看板','自定义导出',NULL),
(UUID_TO_BIN('d45552b8-4d56-51b0-ba04-98f1a89d8a02'),'编辑部门','编辑',NULL),
(UUID_TO_BIN('d4a2ab89-3e49-552d-b0ed-10c4b3a7d479'),'维护薪酬规则','编辑规则',NULL),
(UUID_TO_BIN('df268e1c-31e4-50bd-8159-9bd1b0741885'),'编辑参数','编辑',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000094'),'维护订货宝同步配置','配置与同步',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000120'),'维护销售订单、发货与收退款','维护订单数据',UUID_TO_BIN('019facf2-0000-7000-8000-000000000055')),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000165'),'维护销售规则草稿','保存草稿',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000272'),'读取销售拜访目标','拜访目标',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-00000000027e'),'维护门店投影（临时前置）','同步门店资料',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-00000000028b'),'查看拜访照片证据','查看照片',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-00000000028e'),'维护拜访计划','维护计划',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000296'),'维护业务字典','维护字典',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000361'),'刷新供应链BI数据','同步最新数据',UUID_TO_BIN('019facf2-0000-7000-8000-000000000065')),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000387'),'同步HR员工主档','同步员工',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000388'),'维护HR岗位职位','维护岗位',NULL),
(UUID_TO_BIN('0c6541ce-c4d8-5e0e-9c2e-e88e2eb653b7'),'确认损耗记录','确认',NULL),
(UUID_TO_BIN('0db1c838-0479-5fed-91d2-17e8414fea7c'),'删除部门','删除',NULL),
(UUID_TO_BIN('1a851d93-f970-52f1-81f4-1b6bf8b9bef4'),'变更员工状态','变更状态',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000291'),'维护客户与客户基础数据','维护客户基础资料',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000283'),'维护商品、分类、品牌与规格','维护商品资料',UUID_TO_BIN('019facf2-0000-7000-8000-000000000167')),
(UUID_TO_BIN('31c7b1ae-5bf5-49df-8284-4489c44e2682'),'门店拜访','登记拜访',NULL),
(UUID_TO_BIN('370271c6-498a-5716-9386-6e6374545870'),'确认薪酬','确认',NULL),
(UUID_TO_BIN('5b4fca74-ab9b-5393-8e0a-fe15ade0c7c7'),'确认进货记录','确认',NULL),
(UUID_TO_BIN('627b24d8-55ed-54cd-a0e2-d2260a7c76a5'),'确认报销','确认',NULL),
(UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500012'),'维护货拉拉授权及员工关联','配置授权与员工关联',NULL),
(UUID_TO_BIN('7ebb0f15-6442-5cfe-a4ab-a314c48877d8'),'编辑用户','编辑',NULL),
(UUID_TO_BIN('cc64f0ac-be1d-552d-a5e5-f31e94829578'),'确认营销费用成本','确认',NULL),
(UUID_TO_BIN('d62532ef-1cb7-59b4-8513-7ad6823326a8'),'确认场地费用','确认',NULL),
(UUID_TO_BIN('f180be07-334e-5431-95fd-fefe861e13f3'),'编辑菜单','编辑',NULL),
(UUID_TO_BIN('32c7b1ae-5bf5-49df-8284-4489c44e2682'),'核验与撤销拜访','核验拜访',NULL),
(UUID_TO_BIN('8be20b76-0c06-4eb7-b2e1-1030df639cda'),'核实门店经营状态','核实经营状态',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000273'),'执行销售签到签退','签到与签退',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-00000000027f'),'维护门店归属（临时前置）','维护门店归属',NULL),
(UUID_TO_BIN('0e2c6997-26f3-5c43-b8db-2aced1529987'),'作废进货记录','作废',NULL),
(UUID_TO_BIN('144ccf9d-1622-55a9-a336-a65cde70ff9a'),'登记薪酬发放','登记发放',NULL),
(UUID_TO_BIN('28d66080-0a57-535e-a272-0d891aee07ca'),'作废报销','作废',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000287'),'查询ERP采购与库存数据','查看采购与库存',NULL),
(UUID_TO_BIN('4dc6d4b9-e3ef-54d1-aac5-ff69382f3c56'),'作废损耗记录','作废',NULL),
(UUID_TO_BIN('560c16d0-fcb1-5e84-bbee-2054c2531ca6'),'删除菜单','删除',NULL),
(UUID_TO_BIN('5a63e4a0-3be4-5fac-b169-bf3b992fe1f6'),'作废场地费用','作废',NULL),
(UUID_TO_BIN('626dc030-7965-4c93-aa59-395762c78702'),'发布薪酬方案','发布',NULL),
(UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500013'),'同步货拉拉企业订单及员工','同步订单与员工',NULL),
(UUID_TO_BIN('01a1cf05-0000-7000-8000-000000000001'),'查看外勤核查事项','查看核查事项',NULL),
(UUID_TO_BIN('7ecd3358-e521-5939-952b-37ce3f16a319'),'作废营销费用成本','作废',NULL),
(UUID_TO_BIN('8803ef56-e75d-568a-8053-4ec7cce9812e'),'作废运输记录','作废',NULL),
(UUID_TO_BIN('9538b7bb-8de8-4df2-9d62-c2bef4566c16'),'删除员工','删除',NULL),
(UUID_TO_BIN('ae17de1d-958d-5c81-ba15-29d33dfbb7d8'),'删除角色','删除',NULL),
(UUID_TO_BIN('e0de4d6c-9091-5168-bea7-0b0d10e2515a'),'禁用用户','禁用',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000274'),'提交销售定位证据','上报定位',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000288'),'维护采购、库存与供应商','维护采购与库存',NULL),
(UUID_TO_BIN('691faa79-2d5d-45c3-9868-34d079500014'),'处理本租户货拉拉待关联订单及账单差异','处理待关联订单',NULL),
(UUID_TO_BIN('01a1cf05-0000-7000-8000-000000000002'),'处理外勤核查事项','处理核查事项',NULL),
(UUID_TO_BIN('a9930278-a152-5bfb-9506-d0d05af07b7f'),'配置授权','编辑授权',NULL),
(UUID_TO_BIN('fd1503ab-8fcd-5c0c-9f7c-946e2216a471'),'删除用户','删除',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000278'),'查询本人拜访','我的拜访',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000397'),'维护运营跟进','登记跟进',UUID_TO_BIN('019facf2-0000-7000-8000-000000000065')),
(UUID_TO_BIN('dc31cf89-5c93-490b-aad3-1e1363a10560'),'删除已作废运输记录','删除',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000279'),'创建拜访和签退','开始与结束拜访',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-00000000027a'),'查询附近门店','附近门店',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000304'),'查询商品等级价','查看',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000305'),'维护商品等级价','维护价格',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-00000000027b'),'查询本人当日轨迹','我的轨迹',NULL),
(UUID_TO_BIN('a884412c-c549-5d1f-ac3b-63afc7f74084'),'分配或移交客户主责','分配主责',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000280'),'查询本人拜访录音','查看录音',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000281'),'上传拜访录音片段','上传录音',NULL),
(UUID_TO_BIN('0c8d19c1-e74a-5829-958b-68711fc20470'),'新增客户','新增',NULL),
(UUID_TO_BIN('9cf9110b-3f3a-52b6-ae69-1d322169cc3c'),'编辑客户资料','编辑',NULL),
(UUID_TO_BIN('c6bf4606-bd7f-5c86-b269-cbff0888018b'),'删除客户','删除',NULL),
(UUID_TO_BIN('c54f5b1f-3f40-5ef7-bbda-97325a4eeb5f'),'新增订单','补录订单',UUID_TO_BIN('019facf2-0000-7000-8000-000000000084')),
(UUID_TO_BIN('e6004110-062a-5341-ac15-a5a9ffda9370'),'编辑草稿','编辑草稿',UUID_TO_BIN('019facf2-0000-7000-8000-000000000055')),
(UUID_TO_BIN('a563f544-bb1a-5d3b-bd1d-e65455954b39'),'提交订单','保存并提交',UUID_TO_BIN('019facf2-0000-7000-8000-000000000084')),
(UUID_TO_BIN('e759d1f0-6844-527e-b9b9-350dd39af52c'),'取消订单','取消订单',UUID_TO_BIN('019facf2-0000-7000-8000-000000000055')),
(UUID_TO_BIN('bdf1ac70-70fe-5a48-8e70-5b33d36c065e'),'删除草稿','删除',NULL),
(UUID_TO_BIN('c8c72a57-fb54-51df-bd71-39bb7585e068'),'选择订单仓库','选仓',UUID_TO_BIN('019facf2-0000-7000-8000-000000000055')),
(UUID_TO_BIN('5791bcf5-21ba-5e21-9f2b-7e6ed4918c09'),'确认订单出库','确认出库',UUID_TO_BIN('a3011cd7-ec20-57ee-826c-5c39f0a2a7ec')),
(UUID_TO_BIN('019facf2-0000-7000-8000-000000000289'),'查询本人拜访照片证据','查看拜访照片',NULL),
(UUID_TO_BIN('6ad67d3e-7f3a-4ba4-9be8-495270cd2bcb'),'申请与登记发票','办理发票',UUID_TO_BIN('e1582a53-c489-423e-8b58-40a6bbc04b37')),
(UUID_TO_BIN('019facf2-0000-7000-8000-00000000028a'),'拍摄并上传拜访门头照','上传拜访照片',NULL),
(UUID_TO_BIN('213cce14-158a-439e-a1c3-b25f0cedb936'),'核对历史商品','商品复核',UUID_TO_BIN('019facf2-0000-7000-8000-000000000055')),
(UUID_TO_BIN('ca0a7697-9152-58c3-bd2c-b37ecb110c96'),'查看仓库','查看',NULL),
(UUID_TO_BIN('951d39ef-1938-590d-aa9f-cba3c08c6536'),'新增仓库','新增',NULL),
(UUID_TO_BIN('ed947d06-43ba-54cf-9e8f-e3f737c53dc4'),'编辑仓库','编辑',NULL),
(UUID_TO_BIN('a958e125-9e68-5b52-ab68-1399d3a3b7bf'),'删除仓库','删除',NULL),
(UUID_TO_BIN('9d2a1aa3-58e5-5efd-a16b-35a5ca16ddab'),'查看库存余额','查看库存',NULL),
(UUID_TO_BIN('e2e8dfa3-41bf-541b-98e6-83182c98405c'),'查看入库单','查看',NULL),
(UUID_TO_BIN('627e5c29-a46e-5fbe-bf92-6e61ebf464e9'),'确认采购入库','采购入库',NULL),
(UUID_TO_BIN('6a27b266-05a8-53cd-a7e8-ca2d478363ce'),'查看出库单','查看',NULL),
(UUID_TO_BIN('cf3bfb78-ceb3-54ba-a8c1-5d87d7afa2ba'),'查看调拨单','查看',NULL),
(UUID_TO_BIN('6e2f0f20-7a22-5318-b9b5-f35341eb088c'),'新增调拨单','新增',NULL),
(UUID_TO_BIN('019facf2-0000-7000-8000-00000000028c'),'查询本人今日拜访计划','今日计划',NULL),
(UUID_TO_BIN('56ed7b60-3c6d-4f50-a5bb-00ad20193371'),'查看归属复核','归属复核',UUID_TO_BIN('019facf2-0000-7000-8000-000000000055')),
(UUID_TO_BIN('bb14dc0a-01e4-50da-a8c2-690767a24710'),'编辑调拨单','编辑',NULL),
(UUID_TO_BIN('23bba268-2b60-48c3-992c-53d3e6b32b92'),'申请归属更正','提交复核申请',UUID_TO_BIN('019facf2-0000-7000-8000-000000000055')),
(UUID_TO_BIN('55984c0f-299e-52f7-8a63-d7d932330975'),'删除调拨单','删除',NULL),
(UUID_TO_BIN('084b0888-f65f-552d-8baa-df45e3384ad1'),'确认调拨出库','调拨出库',NULL),
(UUID_TO_BIN('cb775166-14ab-435c-8acf-e23e91de765c'),'审核归属更正','审核更正',UUID_TO_BIN('019facf2-0000-7000-8000-000000000055')),
(UUID_TO_BIN('cb1894c6-7c77-5d01-921e-d8083ec984f9'),'确认调拨入库','调拨入库',NULL),
(UUID_TO_BIN('0984a1b1-0cbf-5dac-8b93-bf507d6b8e47'),'查看销售退款','查看',NULL),
(UUID_TO_BIN('2433e60c-7e10-5e19-86f7-03e24d7de473'),'查看资金单据','查看',NULL),
(UUID_TO_BIN('6214afe0-6d56-5094-8c13-608bb8a39a8d'),'查看销售发货单','查看',NULL),
(UUID_TO_BIN('8ac4b57e-7cf9-521e-a7b3-1243777eccc7'),'查看采购订单','查看',NULL),
(UUID_TO_BIN('a10b2e2c-a8ea-5407-9852-cc4c570c6192'),'查看销售回款','查看',NULL),
(UUID_TO_BIN('2d99e3ff-6bf6-59af-9ab4-9ad6fed2146b'),'新增资金单据','新增',NULL),
(UUID_TO_BIN('871a48c3-b560-53dc-8ae3-160a1bbf4296'),'新增采购订单','新增',NULL),
(UUID_TO_BIN('c4c11307-5600-5ed4-a87a-4c94118e9747'),'新增销售发货单','新增',NULL),
(UUID_TO_BIN('e6c69358-70cb-5721-aa45-eb327fd83459'),'新增销售退款','新增',NULL),
(UUID_TO_BIN('f9c34227-7088-553f-9fe4-452627cdcfea'),'新增销售回款','新增',NULL),
(UUID_TO_BIN('1975538a-4efc-5216-9a3d-a56efd43935e'),'编辑销售回款','编辑',NULL),
(UUID_TO_BIN('86ac55d9-ae40-546e-aa94-3cbbbd6a441c'),'编辑销售发货单','编辑',NULL),
(UUID_TO_BIN('8db49498-3cbb-5cba-b93d-c2576e24e5f4'),'编辑销售退款','编辑',NULL),
(UUID_TO_BIN('bbfcc3e4-f9c2-5263-8e95-ba86f295eb09'),'编辑采购订单','编辑',NULL),
(UUID_TO_BIN('e4b85797-09af-5d3a-96bf-c2a00ec7367e'),'编辑资金单据','编辑',NULL),
(UUID_TO_BIN('127ab51a-ccae-5f02-af46-d25b486884de'),'删除销售发货单','删除',NULL),
(UUID_TO_BIN('550de253-7f6f-5741-b954-1dc4214919e8'),'删除销售回款','删除',NULL),
(UUID_TO_BIN('9505eb65-5f11-5ca4-812c-2d6dcc694c4c'),'删除资金单据','删除',NULL),
(UUID_TO_BIN('c3c25bfa-8497-5f8a-9f86-8f3b75de85b5'),'删除采购订单','删除',NULL),
(UUID_TO_BIN('f86b3526-ad04-51ee-9743-27e5113ff3fd'),'删除销售退款','删除',NULL);

CREATE TEMPORARY TABLE scm_actions(id BINARY(16) PRIMARY KEY,parent BINARY(16),old_id BINARY(16),code VARCHAR(128),name VARCHAR(128),seq INT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO scm_actions VALUES
(UUID_TO_BIN('a76e9802-a03b-525e-8759-5ffe455fda4d'),UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),UUID_TO_BIN('bc113c0e-38e2-5a2e-9a22-774a20dcf9e3'),'hr:department:create-child','新增子部门',5),
(UUID_TO_BIN('502852e1-ca3c-5aa4-b8ba-f61ebc2cb530'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000101'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000119'),'order:export','导出',201),
(UUID_TO_BIN('0163a029-6832-5936-80ce-eef27d1fe3c4'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000101'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000119'),'order:monthly-performance:export','月业绩导出',202),
(UUID_TO_BIN('bf138cf7-bf6d-58f5-b923-80ee02acea25'),UUID_TO_BIN('a48ab364-aa49-40c1-97c5-edcb74623530'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000119'),'order:line:export','导出',201),
(UUID_TO_BIN('b37396e5-20ff-5e3e-a4aa-8c9236b23530'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000340'),UUID_TO_BIN('a10b2e2c-a8ea-5407-9852-cc4c570c6192'),'order:payment:export','导出',201),
(UUID_TO_BIN('87e8e5f7-a9ac-5a19-bb40-c4d74b1b01dc'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000302'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000283'),'erp:specification:create','新增',10),
(UUID_TO_BIN('9d7ec6ed-57f8-5164-aadd-fa48fd61ae0f'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000302'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000283'),'erp:specification:update','编辑',20),
(UUID_TO_BIN('ff6efdbf-e224-5db6-898f-c805f93033d6'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000302'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000283'),'erp:specification:delete','删除',30);

UPDATE iam_resource r JOIN scm_rename a ON a.id=r.id
SET r.display_name=a.name,r.parent_id=COALESCE(a.parent,r.parent_id),r.version=r.version+1,r.updated_at=UTC_TIMESTAMP(6)
WHERE r.application_id=@app AND (BINARY r.display_name<>BINARY a.name OR (a.parent IS NOT NULL AND NOT(r.parent_id<=>a.parent)));
UPDATE iam_app_menu_node n JOIN scm_rename a ON a.id=n.resource_id
LEFT JOIN iam_app_menu_node p ON p.tenant_id=n.tenant_id AND p.application_id=n.application_id AND p.resource_id=a.parent AND p.deleted_at IS NULL
SET n.display_name=IF(BINARY n.display_name=BINARY a.old_name,a.name,n.display_name),n.parent_id=COALESCE(p.id,n.parent_id),n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE n.application_id=@app AND n.deleted_at IS NULL AND ((BINARY n.display_name=BINARY a.old_name AND BINARY a.old_name<>BINARY a.name) OR (p.id IS NOT NULL AND NOT(n.parent_id<=>p.id)));

DELETE g FROM iam_app_role_grant g JOIN iam_app_menu_node n ON n.tenant_id=g.tenant_id AND n.application_id=g.application_id AND n.id=g.menu_node_id JOIN scm_retired a ON a.id=n.resource_id WHERE n.application_id=@app;
DELETE g FROM iam_role_resource g JOIN scm_retired a ON a.id=g.resource_id;
DELETE c FROM iam_tenant_menu_config c JOIN scm_retired a ON a.id=c.resource_id;
DELETE pr FROM iam_package_resource pr JOIN scm_retired a ON a.id=pr.resource_id;
DELETE ui FROM iam_resource_ui ui JOIN scm_retired a ON a.id=ui.resource_id;

DELETE n FROM iam_app_menu_node n JOIN scm_retired a ON a.id=n.resource_id LEFT JOIN iam_app_menu_node child ON child.tenant_id=n.tenant_id AND child.application_id=n.application_id AND child.parent_id=n.id WHERE n.application_id=@app AND child.id IS NULL;

DELETE n FROM iam_app_menu_node n JOIN scm_retired a ON a.id=n.resource_id LEFT JOIN iam_app_menu_node child ON child.tenant_id=n.tenant_id AND child.application_id=n.application_id AND child.parent_id=n.id WHERE n.application_id=@app AND child.id IS NULL;

DELETE n FROM iam_app_menu_node n JOIN scm_retired a ON a.id=n.resource_id LEFT JOIN iam_app_menu_node child ON child.tenant_id=n.tenant_id AND child.application_id=n.application_id AND child.parent_id=n.id WHERE n.application_id=@app AND child.id IS NULL;

DELETE n FROM iam_app_menu_node n JOIN scm_retired a ON a.id=n.resource_id LEFT JOIN iam_app_menu_node child ON child.tenant_id=n.tenant_id AND child.application_id=n.application_id AND child.parent_id=n.id WHERE n.application_id=@app AND child.id IS NULL;

DELETE n FROM iam_app_menu_node n JOIN scm_retired a ON a.id=n.resource_id LEFT JOIN iam_app_menu_node child ON child.tenant_id=n.tenant_id AND child.application_id=n.application_id AND child.parent_id=n.id WHERE n.application_id=@app AND child.id IS NULL;

DELETE n FROM iam_app_menu_node n JOIN scm_retired a ON a.id=n.resource_id LEFT JOIN iam_app_menu_node child ON child.tenant_id=n.tenant_id AND child.application_id=n.application_id AND child.parent_id=n.id WHERE n.application_id=@app AND child.id IS NULL;

DELETE n FROM iam_app_menu_node n JOIN scm_retired a ON a.id=n.resource_id LEFT JOIN iam_app_menu_node child ON child.tenant_id=n.tenant_id AND child.application_id=n.application_id AND child.parent_id=n.id WHERE n.application_id=@app AND child.id IS NULL;

DELETE r FROM iam_resource r JOIN scm_retired a ON a.id=r.id WHERE r.application_id=@app AND a.depth=4;

DELETE r FROM iam_resource r JOIN scm_retired a ON a.id=r.id WHERE r.application_id=@app AND a.depth=3;

DELETE r FROM iam_resource r JOIN scm_retired a ON a.id=r.id WHERE r.application_id=@app AND a.depth=2;

DELETE r FROM iam_resource r JOIN scm_retired a ON a.id=r.id WHERE r.application_id=@app AND a.depth=1;

DELETE a FROM scm_actions a JOIN iam_resource existing ON existing.id=a.id;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
SELECT a.id,@app,a.parent,CONCAT('SUPPLY_CHAIN.ACTION.',UPPER(REPLACE(a.code,':','.'))),'BUTTON',a.code,a.name,a.seq,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM scm_actions a JOIN iam_resource p ON p.id=a.parent AND p.application_id=@app JOIN iam_resource src ON src.id=a.old_id AND src.application_id=@app
LEFT JOIN iam_resource old ON old.id=a.id WHERE old.id IS NULL;
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT src.package_version_id,a.id,UTC_TIMESTAMP(6) FROM scm_actions a JOIN iam_package_resource src ON src.resource_id=a.old_id
JOIN iam_package_resource p ON p.resource_id=a.parent AND p.package_version_id=src.package_version_id
LEFT JOIN iam_package_resource old ON old.resource_id=a.id AND old.package_version_id=src.package_version_id WHERE old.resource_id IS NULL;
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT p.tenant_id,@app,a.id,p.id,a.id,'BUTTON',a.name,a.seq,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM scm_actions a JOIN iam_app_menu_node p ON p.resource_id=a.parent AND p.application_id=@app AND p.deleted_at IS NULL
JOIN iam_app_menu_node src ON src.resource_id=a.old_id AND src.tenant_id=p.tenant_id AND src.application_id=@app AND src.deleted_at IS NULL AND src.status='ACTIVE'
LEFT JOIN iam_app_menu_node old ON old.tenant_id=p.tenant_id AND old.application_id=@app AND old.resource_id=a.id WHERE old.id IS NULL;
INSERT INTO iam_app_role_grant(tenant_id,application_id,role_id,menu_node_id)
SELECT g.tenant_id,@app,g.role_id,n.id FROM scm_actions a JOIN iam_app_menu_node src ON src.resource_id=a.old_id AND src.application_id=@app AND src.deleted_at IS NULL AND src.status='ACTIVE'
JOIN iam_app_role_grant g ON g.menu_node_id=src.id AND g.tenant_id=src.tenant_id AND g.application_id=@app
JOIN iam_app_menu_node n ON n.tenant_id=src.tenant_id AND n.application_id=@app AND n.resource_id=a.id AND n.deleted_at IS NULL
LEFT JOIN iam_app_role_grant old ON old.tenant_id=g.tenant_id AND old.application_id=@app AND old.role_id=g.role_id AND old.menu_node_id=n.id WHERE old.role_id IS NULL;
INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT src.tenant_id,src.role_id,a.id,src.status,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM scm_actions a JOIN iam_role_resource src ON src.resource_id=a.old_id
LEFT JOIN iam_role_resource old ON old.tenant_id=src.tenant_id AND old.role_id=src.role_id AND old.resource_id=a.id WHERE old.role_id IS NULL;

DELETE FROM iam_app_menu_node WHERE tenant_id=UUID_TO_BIN('019fbaf9-cfb5-740d-b347-739d29765d8e') AND application_id=@app AND id=UUID_TO_BIN('9b378f22-f0c6-4f6b-a910-2d7f4b186590') AND resource_id IS NULL AND deleted_at IS NOT NULL;

UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE application_id=@app;
UPDATE iam_tenant t JOIN iam_app_settings s ON s.tenant_id=t.id AND s.application_id=@app SET t.policy_version=t.policy_version+1,t.version=t.version+1,t.updated_at=UTC_TIMESTAMP(6);

DROP TEMPORARY TABLE scm_actions,scm_rename,scm_retired;
