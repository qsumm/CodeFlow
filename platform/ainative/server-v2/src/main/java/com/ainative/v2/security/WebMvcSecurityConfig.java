package com.ainative.v2.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcSecurityConfig implements WebMvcConfigurer {
    private final PermissionInterceptor interceptor;
    private final AuditWriteInterceptor auditWrite;

    public WebMvcSecurityConfig(PermissionInterceptor interceptor, AuditWriteInterceptor auditWrite) {
        this.interceptor = interceptor;
        this.auditWrite = auditWrite;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
        // 写操作统一审计(谁在什么时候动了什么),挂最外层
        registry.addInterceptor(auditWrite).addPathPatterns("/api/**");
    }
}
