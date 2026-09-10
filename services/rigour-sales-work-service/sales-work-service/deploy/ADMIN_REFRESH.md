# 后台刷新与预览限流

## 已确认问题

2026-09-08线上错误日志确认后台文档、admin.css、admin.js、列表、每日统计与新照片API同用 sales_checkin_admin 的30次/分钟、burst10额度。正常一页20张照片足以耗尽额度，继续返回/刷新会连样式、脚本也得到503。原5并发计数与照片共用，也出现误拒绝。此前仅验证首次匿名页面不足以覆盖这个场景。

## 当前配置

- 页面、固定CSS/JS及公共图标不消耗业务请求的限流额度。
- 管理只读查询：10次/秒、burst40、20并发。
- 新旧媒体GET/HEAD路由：20次/秒、burst60、20并发。
- 登录与其他非GET/HEAD管理操作：保留30次/分钟、burst10、5并发，与读取和预览隔离。
- 管理接口真实超限返回429；公开API、定位、个人码与上传体积限制保持原规则。
- 不使用Cookie是否存在作为免限流依据；所有媒体、查询和导出仍由Java会话、城市与租户权限校验。

Nginx map按方法与规范化URI分类；空键不计入请求和并发额度。静态免限流仅管理页、index.html、admin.css/admin.js固定名单，其他管理GET仍计入读取额度。参见[Nginx请求限流](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)与[连接限流](https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html)。

## 发布

必须同时安装deploy/nginx/sales-checkin-limits.conf到/etc/nginx/conf.d/，以及sales-checkin-locations.conf到/etc/nginx/snippets/，执行nginx -t通过后reload。先备份两文件，不触碰私有proxy-marker snippet。回退亦需成对恢复。仅改代码或仅更新locations会遗漏map/zone定义。

## 验证

- 本地真实Nginx1.18和合成上游：旧配置重现503，新配置连续4轮进入/20图加载/刷新/返回成功，分别覆盖实际超额429与静态/读/写/媒体互不挤占。
- 本地Playwright：三个汇总表头升降序、URL恢复、分页归零、Excel携带同组排序；瞬时读取失败有界重试，持续失败保留手动恢复，写操作不自动重放。
- 真实MySQL：六种汇总排序跨页、稳定同名顺序、权限隔离、Excel汇总与明细各自排序。
- 线上只读连续刷新与导航；不得通过新建实际业务数据做验收。

## 本轮实际结果

- Sales完整verify191项通过，shared依赖24项通过；Node71项通过。
- 排序与恢复Chrome55项、WebKit55项通过；原后台统计32项、媒体71项通过。
- Nginx1.18回归246断言通过；旧配置18次503，新配置416次响应全部200。
- WebKit补修：等待样式实际load/error完成再初始化；日期筛选重置调用原生form.reset()清理内部编辑状态，随后按授权城市及默认排序写回。以正常鼠标查询路径验证，无键盘绕过。
- 最后静态资源修复经浏览器验证后重新打包，并核对JAR与全部源码静态文件一致。
- 全仓verify仍被既有IAM V52约束问题阻断，IAM不在本轮修改和发布范围内。
