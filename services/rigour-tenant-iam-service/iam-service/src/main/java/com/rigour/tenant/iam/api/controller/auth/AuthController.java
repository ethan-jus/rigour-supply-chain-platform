package com.rigour.tenant.iam.api.controller.auth;

import com.rigour.tenant.iam.infrastructure.security.oidc.OidcServerProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 账号登录、飞书身份交换、刷新、退出和当前用户查询的 HTTP 边界。
 *
 * <p>表单只收集登录输入并交给Spring Security，不在页面中实现令牌和权限规则。</p>
 */
@Controller
public final class AuthController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final OidcServerProperties oidcServerProperties;

    public AuthController(OidcServerProperties oidcServerProperties) {
        this.oidcServerProperties = oidcServerProperties;
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> loginPage(
            HttpServletRequest request,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "logout", required = false) String logout
    ) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken == null) {
            csrfToken = (CsrfToken) request.getAttribute("_csrf");
        }
        if (csrfToken == null) {
            throw new IllegalStateException("CSRF token is unavailable");
        }
        String scriptNonce = scriptNonce();
        String portalFormActions = String.join(" ", oidcServerProperties.requireAllowedOrigins());
        String message = error != null
                ? "<div class=\"notice notice--error\" role=\"alert\">账号、密码或登录范围不正确，请重试。</div>"
                : logout != null ? "<div class=\"notice notice--success\" role=\"status\">你已安全退出统一门户。</div>" : "";
        String html = """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
                <meta name="color-scheme" content="light"><title>瑞盖统一身份认证</title><style>
                *{box-sizing:border-box}body{margin:0;min-height:100vh;font-family:Inter,"PingFang SC","Microsoft YaHei",system-ui,sans-serif;color:#14213d;background:#f3f6fb}
                .page{min-height:100vh;display:grid;grid-template-columns:minmax(300px,1.08fr) minmax(420px,.92fr)}
                .brand{position:relative;overflow:hidden;padding:clamp(42px,7vw,96px);display:flex;flex-direction:column;justify-content:space-between;color:#fff;background:linear-gradient(145deg,#102a56 0%%,#174a8b 58%%,#1c6bb5 100%%)}
                .brand:after{content:"";position:absolute;width:420px;height:420px;right:-160px;bottom:-170px;border-radius:50%%;border:80px solid rgba(255,255,255,.08)}
                .logo{display:flex;align-items:center;gap:12px;font-size:18px;font-weight:700;letter-spacing:.04em}.logo-mark{width:38px;height:38px;border-radius:12px;display:grid;place-items:center;background:#fff;color:#174a8b}
                .intro{position:relative;z-index:1;max-width:600px}.intro h1{margin:0 0 18px;font-size:clamp(34px,4vw,56px);line-height:1.14;letter-spacing:-.03em}.intro p{margin:0;color:rgba(255,255,255,.78);font-size:16px;line-height:1.9}
                .capabilities{position:relative;z-index:1;display:flex;gap:10px;flex-wrap:wrap}.capabilities span{padding:8px 12px;border:1px solid rgba(255,255,255,.2);border-radius:999px;background:rgba(255,255,255,.08);font-size:13px}
                .auth{display:grid;place-items:center;padding:32px}.card{width:min(440px,100%%);padding:40px;border:1px solid #e2e8f0;border-radius:22px;background:#fff;box-shadow:0 22px 60px rgba(15,42,82,.12)}
                .eyebrow{margin:0 0 8px;color:#1b62a6;font-size:13px;font-weight:700;letter-spacing:.12em}.card h2{margin:0;font-size:28px}.subtitle{margin:10px 0 26px;color:#64748b;line-height:1.7}
                form{display:grid;gap:18px}label{display:grid;gap:8px;font-size:14px;font-weight:600;color:#334155}.hint{font-size:12px;font-weight:400;color:#94a3b8}
                input,select,button{width:100%%;font:inherit;border-radius:10px}input,select{height:46px;padding:0 13px;border:1px solid #cbd5e1;background:#fff;color:#0f172a;outline:none}input:focus,select:focus{border-color:#2874bd;box-shadow:0 0 0 3px rgba(40,116,189,.12)}
                button{height:48px;margin-top:2px;border:0;background:#1764ad;color:#fff;font-weight:700;cursor:pointer;box-shadow:0 8px 18px rgba(23,100,173,.22)}button:hover:not(.is-pending){background:#12558f}button:focus-visible{outline:3px solid rgba(40,116,189,.32);outline-offset:2px}button.is-pending{pointer-events:none;cursor:wait;opacity:.72;box-shadow:none}
                .button-content{display:inline-flex;align-items:center;justify-content:center;gap:9px}.spinner{width:17px;height:17px;border:2px solid rgba(255,255,255,.42);border-top-color:#fff;border-radius:50%%;animation:spin .7s linear infinite}[hidden]{display:none!important}@keyframes spin{to{transform:rotate(360deg)}}
                .notice{margin-bottom:18px;padding:11px 13px;border-radius:9px;font-size:13px;line-height:1.5}.notice--error{color:#9f1d20;background:#fff1f1;border:1px solid #fecaca}.notice--success{color:#166534;background:#f0fdf4;border:1px solid #bbf7d0}
                .security{margin:22px 0 0;padding-top:18px;border-top:1px solid #edf2f7;color:#64748b;font-size:12px;line-height:1.7}
                @media(max-width:860px){.page{grid-template-columns:1fr}.brand{min-height:250px;padding:34px}.intro h1{font-size:34px}.capabilities{margin-top:30px}.auth{padding:24px}.card{padding:30px}}
                @media(max-width:480px){.brand{min-height:220px}.intro p,.capabilities{display:none}.auth{padding:16px}.card{padding:24px;border-radius:16px}}
                </style></head><body><main class="page"><section class="brand" aria-label="平台介绍">
                <div class="logo"><span class="logo-mark">R</span><span>瑞盖优选·统一门户</span></div>
                <div class="intro"><h1>一次登录，<br>连接全部业务系统。</h1><p>统一身份、应用入口和权限策略，让总部、城市和销售团队在同一个安全边界中协作。</p></div>
                <div class="capabilities"><span>统一应用入口</span><span>多租户隔离</span><span>角色与数据权限</span></div></section>
                <section class="auth"><div class="card"><p class="eyebrow">IDENTITY ACCESS</p><h2>登录工作台</h2><p class="subtitle">使用由管理员分配的企业账号。</p>%s
                <div id="login-timeout" class="notice notice--error" role="alert" hidden>登录请求已返回，但页面未完成跳转，请重新提交。</div>
                <form id="login-form" method="post" action="/login" autocomplete="on">
                <input type="hidden" name="%s" value="%s">
                <label>登录身份<select name="principalScope" aria-label="登录身份"><option value="TENANT">企业/租户用户</option><option value="PLATFORM">平台管理员</option></select></label>
                <label>企业编码 <span class="hint">平台管理员可留空</span><input name="tenantCode" maxlength="32" autocomplete="organization" placeholder="例如 RIGOUR-SH"></label>
                <label>用户名<input name="username" maxlength="64" required autocomplete="username" autofocus placeholder="请输入用户名"></label>
                <label>密码<input type="password" name="password" minlength="14" maxlength="128" required autocomplete="current-password" placeholder="请输入密码"></label>
                <button id="login-submit" type="submit"><span id="login-idle" class="button-content">安全登录</span><span id="login-pending" class="button-content" hidden><span class="spinner" aria-hidden="true"></span>正在验证身份…</span></button></form><p class="security">登录使用 OIDC Authorization Code + PKCE。密码只提交给 IAM，不会传递给门户或其他应用。</p></div></section></main>
                <script nonce="%s">const form=document.getElementById('login-form');const button=document.getElementById('login-submit');const idle=document.getElementById('login-idle');const pending=document.getElementById('login-pending');const timeout=document.getElementById('login-timeout');let submitted=false;let resetTimer;const reset=()=>{submitted=false;form.removeAttribute('aria-busy');button.removeAttribute('aria-disabled');button.classList.remove('is-pending');idle.hidden=false;pending.hidden=true;timeout.hidden=false};form.addEventListener('submit',event=>{if(submitted){event.preventDefault();return}submitted=true;timeout.hidden=true;form.setAttribute('aria-busy','true');button.setAttribute('aria-disabled','true');button.classList.add('is-pending');idle.hidden=true;pending.hidden=false;resetTimer=setTimeout(reset,12000)});window.addEventListener('pagehide',()=>clearTimeout(resetTimer),{once:true});</script></body></html>
                """.formatted(message,
                HtmlUtils.htmlEscape(csrfToken.getParameterName()),
                HtmlUtils.htmlEscape(csrfToken.getToken()),
                scriptNonce);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-" + scriptNonce
                                + "'; form-action 'self' " + portalFormActions
                                + "; frame-ancestors 'none'; base-uri 'none'")
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(html);
    }

    private static String scriptNonce() {
        byte[] nonce = new byte[18];
        SECURE_RANDOM.nextBytes(nonce);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
    }
}
