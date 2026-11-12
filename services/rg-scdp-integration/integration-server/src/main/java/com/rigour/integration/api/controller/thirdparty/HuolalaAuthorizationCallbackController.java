package com.rigour.integration.api.controller.thirdparty;

import com.rigour.integration.application.service.thirdparty.oauth.ThirdPartyAuthorizationService;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 浏览器回跳进入后端。不会把授权码、Token或底层错误回传前端。 */
@RestController
@RequestMapping("/api/v1/integration/huolala/oauth")
public final class HuolalaAuthorizationCallbackController {
    static final String COOKIE = "rigour_huolala_authorization";
    static final String PATH = "/api/v1/integration/huolala/oauth/callback";
    private final ThirdPartyAuthorizationService service;

    public HuolalaAuthorizationCallbackController(ThirdPartyAuthorizationService service) {
        this.service = service;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam String ticket) {
        var result = service.open("HUOLALA", ticket);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(result.uri())
                .header(
                        HttpHeaders.SET_COOKIE,
                        ResponseCookie.from(COOKIE, result.cookieValue())
                                .httpOnly(true)
                                .secure(result.secure())
                                .sameSite("Lax")
                                .path(PATH)
                                .maxAge(600)
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @CookieValue(name = COOKIE, required = false) String cookie,
            @RequestParam(required = false) String code) {
        var result = service.callback("HUOLALA", cookie, code);
        String message =
                switch (result.status()) {
                    case "SUCCEEDED" -> "企业授权已完成，请关闭本窗口并返回系统。";
                    case "PENDING_VERIFICATION" -> "已收到授权，正在核验企业信息。请返回系统查看结果。";
                    default -> "本次授权未完成，请返回系统重新发起授权。";
                };
        return page(message, HttpStatus.OK);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> invalid() {
        return page("授权未完成或会话已过期，请返回系统重新发起授权。", HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> page(String message, HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(
                        new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header(
                        "Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'")
                .body(
                        "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta"
                            + " name=\"viewport\""
                            + " content=\"width=device-width,initial-scale=1\"><title>企业授权结果</title><h1>货拉拉企业授权</h1><p>"
                                + message
                                + "</p></html>");
    }
}
