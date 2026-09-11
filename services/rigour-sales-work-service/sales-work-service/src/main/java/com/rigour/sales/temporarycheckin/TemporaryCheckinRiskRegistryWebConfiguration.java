package com.rigour.sales.temporarycheckin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 已认证后台的编号补登记在控制器/只读事务之前执行；静态、媒体、公开销售请求不进入此路径。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
public class TemporaryCheckinRiskRegistryWebConfiguration implements WebMvcConfigurer {
    private final TemporaryCheckinRiskService service;
    private final TemporaryCheckinAdminAccessPolicy access;
    public TemporaryCheckinRiskRegistryWebConfiguration(TemporaryCheckinRiskService service,TemporaryCheckinAdminAccessPolicy access) {this.service=service;this.access=access;}
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String path=request.getRequestURI();
                if(!"GET".equals(request.getMethod())||path.contains("/media/"))return true;
                if(path.equals("/sales-checkin/admin/api/v1/options")||path.startsWith("/sales-checkin/admin/api/v1/submissions")
                        ||path.equals("/sales-checkin/admin/export.csv")||path.equals("/sales-checkin/admin/export.xlsx")) {
                    access.requireScope(request);service.ensureRegistry();
                }
                return true;
            }
        }).addPathPatterns("/sales-checkin/admin/**");
    }
}
