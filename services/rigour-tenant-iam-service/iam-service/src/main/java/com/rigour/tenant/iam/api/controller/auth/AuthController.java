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
 * 账号密码登录页的 HTTP 边界。
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
                ? "<div class=\"notice notice--error\" role=\"alert\">账号、密码或企业编码不正确，请重试。</div>"
                : logout != null ? "<div class=\"notice notice--success\" role=\"status\">你已安全退出统一门户。</div>" : "";
        String html = """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
                <meta name="color-scheme" content="light"><title>瑞盖优选 · 统一身份认证</title><style>
                *{box-sizing:border-box}body{margin:0;min-height:100vh;font-family:"PingFang SC","Microsoft YaHei",system-ui,-apple-system,sans-serif;color:#0f172a;background:#fff}
                .page{min-height:100vh;display:grid;grid-template-columns:minmax(380px,46%%) minmax(420px,54%%)}
                .brand{position:relative;overflow:hidden;padding:clamp(40px,5vw,72px);display:flex;flex-direction:column;justify-content:space-between;color:#fff;background:#0b1220}
                .brand:before{content:"";position:absolute;inset:0;background-image:radial-gradient(rgba(255,255,255,.07) 1px,transparent 1px);background-size:22px 22px}
                .brand:after{content:"";position:absolute;width:640px;height:640px;top:-260px;left:-220px;border-radius:50%%;background:radial-gradient(circle,rgba(79,70,229,.28) 0%%,transparent 65%%)}
                .logo{position:relative;z-index:1;display:flex;align-items:center;gap:12px;font-size:15px;font-weight:600;letter-spacing:.02em}
                .logo img{width:40px;height:40px;border-radius:10px;display:block}
                .intro{position:relative;z-index:1;max-width:520px}
                .intro .eyebrow{margin:0 0 18px;color:#64748b;font-size:11px;font-weight:600;letter-spacing:.22em}
                .intro h1{margin:0 0 20px;font-size:clamp(30px,3vw,40px);font-weight:600;line-height:1.28;letter-spacing:-.01em}
                .intro p{margin:0;color:#94a3b8;font-size:15px;line-height:1.8}
                .capabilities{position:relative;z-index:1;display:flex;gap:24px;flex-wrap:wrap;padding-top:20px;border-top:1px solid rgba(255,255,255,.1)}
                .capabilities span{color:#64748b;font-size:12px;letter-spacing:.04em}
                .auth{display:grid;place-items:center;padding:32px}
                .form-column{width:min(400px,100%%)}
                .form-column h2{margin:0;font-size:26px;font-weight:600;color:#0f172a}
                .subtitle{margin:10px 0 30px;color:#64748b;font-size:14px;line-height:1.7}
                form{display:grid;gap:20px}label{display:grid;gap:8px;font-size:13px;font-weight:500;color:#334155}
                input{width:100%%;height:44px;padding:0 14px;font:inherit;font-size:14px;color:#0f172a;background:#fff;border:1px solid #e2e8f0;border-radius:8px;outline:none;transition:border-color .15s,box-shadow .15s}
                input::placeholder{color:#94a3b8}
                input:focus{border-color:#2563eb;box-shadow:0 0 0 3px rgba(37,99,235,.12)}
                .password-field{position:relative}.password-field input{padding-right:44px}
                .password-toggle{position:absolute;right:6px;top:50%%;transform:translateY(-50%%);width:32px;height:32px;padding:0;border:0;background:transparent;color:#94a3b8;cursor:pointer;display:grid;place-items:center;border-radius:6px}
                .password-toggle:hover{color:#475569}.password-toggle:focus-visible{outline:2px solid rgba(37,99,235,.4)}
                button.submit{width:100%%;height:44px;margin-top:4px;font:inherit;font-size:14px;font-weight:600;color:#fff;background:#2563eb;border:0;border-radius:8px;cursor:pointer;box-shadow:0 1px 2px rgba(15,23,42,.08);transition:background .15s}
                button.submit:hover:not(.is-pending){background:#1d4ed8}button.submit:focus-visible{outline:3px solid rgba(37,99,235,.3);outline-offset:2px}
                button.submit.is-pending{pointer-events:none;cursor:wait;opacity:.75}
                .button-content{display:inline-flex;align-items:center;justify-content:center;gap:9px}
                .spinner{width:16px;height:16px;border:2px solid rgba(255,255,255,.42);border-top-color:#fff;border-radius:50%%;animation:spin .7s linear infinite}[hidden]{display:none!important}@keyframes spin{to{transform:rotate(360deg)}}
                .notice{margin-bottom:20px;padding:11px 14px;border-radius:8px;font-size:13px;line-height:1.55}
                .notice--error{color:#b91c1c;background:#fef2f2;border:1px solid #fecaca}.notice--success{color:#166534;background:#f0fdf4;border:1px solid #bbf7d0}
                .agreement{margin:24px 0 0;padding-top:20px;border-top:1px solid #e2e8f0;color:#94a3b8;font-size:12px;line-height:1.8}
                @media(max-width:860px){.page{grid-template-columns:1fr}.brand{min-height:230px;padding:32px}.intro h1{font-size:28px}.capabilities{margin-top:24px}.auth{padding:32px 24px}}
                @media(max-width:480px){.brand{min-height:200px}.intro p,.capabilities{display:none}.auth{padding:24px 16px}}
                </style></head><body><main class="page"><section class="brand" aria-label="平台介绍">
                <div class="logo"><img src="/brand/ruigai-logo.png" alt="瑞盖优选"><span>瑞盖优选</span></div>
                <div class="intro"><p class="eyebrow">RIGOUR IDENTITY</p><h1>从一个入口，<br>进入全部业务系统。</h1><p>统一身份 · 统一权限 · 统一应用目录</p></div>
                <div class="capabilities"><span>多租户隔离</span><span>OIDC + PKCE</span><span>角色与数据权限</span></div></section>
                <section class="auth"><div class="form-column"><h2>登录</h2><p class="subtitle">使用管理员分配的企业账号</p>%s
                <div id="login-timeout" class="notice notice--error" role="alert" hidden>登录请求已返回，但页面未完成跳转，请重新提交。</div>
                <form id="login-form" method="post" action="/login" autocomplete="on">
                <input type="hidden" name="%s" value="%s">
                <label>企业编码<input name="tenantCode" maxlength="32" autocomplete="organization" placeholder="企业租户编码，平台管理员可留空"></label>
                <label>用户名<input name="username" maxlength="64" required autocomplete="username" autofocus placeholder="请输入用户名"></label>
                <label>密码<span class="password-field"><input id="password-input" type="password" name="password" minlength="14" maxlength="128" required autocomplete="current-password" placeholder="请输入密码"><button class="password-toggle" id="password-toggle" type="button" aria-label="显示密码"><svg id="eye-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg></button></span></label>
                <button id="login-submit" class="submit" type="submit"><span id="login-idle" class="button-content">安全登录</span><span id="login-pending" class="button-content" hidden><span class="spinner" aria-hidden="true"></span>正在验证身份…</span></button></form>
                <p class="agreement">登录即代表同意《服务协议》与《隐私政策》。登录使用 OIDC Authorization Code + PKCE，密码仅提交给统一身份服务。</p></div></section></main>
                <script nonce="%s">const form=document.getElementById('login-form');const button=document.getElementById('login-submit');const idle=document.getElementById('login-idle');const pending=document.getElementById('login-pending');const timeout=document.getElementById('login-timeout');const passwordInput=document.getElementById('password-input');const passwordToggle=document.getElementById('password-toggle');passwordToggle.addEventListener('click',()=>{const show=passwordInput.type==='password';passwordInput.type=show?'text':'password';passwordToggle.setAttribute('aria-label',show?'隐藏密码':'显示密码')});let submitted=false;let resetTimer;const reset=()=>{submitted=false;form.removeAttribute('aria-busy');button.removeAttribute('aria-disabled');button.classList.remove('is-pending');idle.hidden=false;pending.hidden=true;timeout.hidden=false};form.addEventListener('submit',event=>{if(submitted){event.preventDefault();return}submitted=true;timeout.hidden=true;form.setAttribute('aria-busy','true');button.setAttribute('aria-disabled','true');button.classList.add('is-pending');idle.hidden=true;pending.hidden=false;resetTimer=setTimeout(reset,12000)});window.addEventListener('pagehide',()=>clearTimeout(resetTimer),{once:true});</script></body></html>
                """.formatted(message,
                HtmlUtils.htmlEscape(csrfToken.getParameterName()),
                HtmlUtils.htmlEscape(csrfToken.getToken()),
                scriptNonce);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy",
                        "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'nonce-" + scriptNonce
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
