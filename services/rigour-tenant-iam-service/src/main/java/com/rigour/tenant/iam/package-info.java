/**
 * 租户、组织、身份、角色、模块许可与统一 DataScope Policy 的单一主写者。
 *
 * <p>边界：独占所属 Schema 和写权限，不直接读取或依赖其他领域服务实现。</p>
 * <p>跨服务协作只能通过版本化 API、领域事件或本地投影完成。</p>
 */
package com.rigour.tenant.iam;
