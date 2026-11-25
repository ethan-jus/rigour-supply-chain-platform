package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesWorkApi;
import com.rigour.sales.application.service.WorkbenchReleasePolicy;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@RestController
public class WorkbenchReleaseController implements WebMvcConfigurer {
    private final WorkbenchReleasePolicy policy;

    public WorkbenchReleaseController(WorkbenchReleasePolicy policy) {
        this.policy = policy;
    }

    @GetMapping(SalesWorkApi.BASE_PATH + "/me/mobile-release/{platform}")
    public ApiResponse<WorkbenchReleasePolicy.Release> release(@PathVariable String platform) {
        AuthorizationContext.requireCurrent();
        return ApiResponse.success(policy.release(platform));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("POST".equals(request.getMethod()))
                    policy.checkBuild(request.getHeader("X-Workbench-Platform"), request.getHeader("X-Workbench-Build"));
                return true;
            }
        }).addPathPatterns(SalesWorkApi.CHECK_IN_PATH, SalesWorkApi.RESUME_WORK_PATH, SalesWorkApi.VISITS_PATH,
                SalesWorkApi.BASE_PATH + "/work/store-visits");
    }
}
