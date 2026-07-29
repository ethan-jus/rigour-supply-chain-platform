/**
 * 请求级上下文模块。
 *
 * <p>职责：解析并暴露 requestId、tenantId 和语言信息，保证请求结束后清理线程本地状态。</p>
 * <p>边界：不做身份认证、租户授权或 DataScope 判定，也不为异步线程隐式传播上下文。</p>
 */
package com.rigour.shared.context;
