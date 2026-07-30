/**
 * 外部连接器、Raw Landing、同步批次、映射、核对、重放与主权状态。
 *
 * <p>边界：独占所属 Schema 和写权限，不直接读取或依赖其他领域服务实现。</p>
 * <p>跨服务协作只能通过版本化 API、领域事件或本地投影完成。</p>
 */
package com.rigour.integration;
