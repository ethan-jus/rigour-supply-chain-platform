package com.rigour.order.infrastructure.cache;
import com.rigour.shared.cache.QueryCache.QueryBusyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public class QueryCacheExceptionHandler {
    @ExceptionHandler(QueryBusyException.class)
    public ResponseEntity<Map<String,String>> busy(QueryBusyException ex) {
        return ResponseEntity.status(503).header("Retry-After","2").body(Map.of("message",ex.getMessage()));
    }
}
