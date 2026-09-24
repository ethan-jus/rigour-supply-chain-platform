package com.rigour.shared.core.web;

import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.exception.RequestValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiHttpContractTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ContractController())
            .setControllerAdvice(new GlobalExceptionHandler(), new ApiEnvelopeAdvice()).build();

    @Test
    void negotiatedEnvelopePreservesLegacyClientsAndDoesNotDoubleWrap() throws Exception {
        mvc.perform(get("/api/v1/contract"))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.code").doesNotExist());
        mvc.perform(get("/api/v1/contract").accept(ApiEnvelopeAdvice.MEDIA_TYPE))
                .andExpect(header().string("Vary", "Accept"))
                .andExpect(jsonPath("$.code").value("OK")).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/v1/wrapped").accept(ApiEnvelopeAdvice.MEDIA_TYPE))
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.code").doesNotExist());
        mvc.perform(get("/oauth2/example").accept(ApiEnvelopeAdvice.MEDIA_TYPE))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void filesAndNoContentKeepTheirProtocols() throws Exception {
        mvc.perform(get("/api/v1/file").accept(ApiEnvelopeAdvice.MEDIA_TYPE + ", */*"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mvc.perform(delete("/api/v1/contract").accept(ApiEnvelopeAdvice.MEDIA_TYPE))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void malformedInputAndMethodErrorsAreNotServerErrors() throws Exception {
        mvc.perform(post("/api/v1/contract").contentType("application/json").content("{bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/v1/number").param("count", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/v1/number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(put("/api/v1/contract"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/contract").contentType("text/plain").content("bad"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void safeBusinessReasonIsVisibleButUnexpectedDetailsStayPrivate() throws Exception {
        mvc.perform(get("/api/v1/reject"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("角色编码不合法"));
        mvc.perform(get("/api/v1/broken"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("服务器内部错误"));
        mvc.perform(get("/api/v1/rate"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后重试"));
    }

    @RestController
    static class ContractController {
        @GetMapping({"/api/v1/contract", "/oauth2/example"}) Map<String, Integer> data() { return Map.of("total", 2); }
        @GetMapping("/api/v1/wrapped") ApiResponse<?> wrapped() { return ApiResponse.success(data()); }
        @GetMapping(value = "/api/v1/file", produces = "application/octet-stream") byte[] file() { return new byte[]{1, 2, 3}; }
        @DeleteMapping("/api/v1/contract") ResponseEntity<Void> deleteItem() { return ResponseEntity.noContent().build(); }
        @PostMapping("/api/v1/contract") Map<String, Object> save(@RequestBody Map<String, Object> body) { return body; }
        @GetMapping("/api/v1/number") int number(@RequestParam int count) { return count; }
        @GetMapping("/api/v1/reject") void reject() { throw new RequestValidationException("角色编码不合法"); }
        @GetMapping("/api/v1/broken") void broken() { throw new IllegalArgumentException("SQL password secret"); }
        @GetMapping("/api/v1/rate") void rate() { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "internal limiter detail"); }
    }
}
