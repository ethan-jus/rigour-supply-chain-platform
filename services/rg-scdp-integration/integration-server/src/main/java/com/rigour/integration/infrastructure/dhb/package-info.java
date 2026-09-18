/**
 * 订货宝出站适配器。
 *
 * <p>这里集中处理供应商协议细节和 Secret 引用；应用层只能依赖
 * {@code application.port.out.DhbClient}，不能复制 HTTP 或认证逻辑。</p>
 */
package com.rigour.integration.infrastructure.dhb;
