package com.ainative.v2.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 前端静态托管 + SPA 回退。
 *
 * <p>发布形态:vite 构建产物(dist)拷入 classpath:/frontend/,与后端同 jar 同源服务,
 * 前端无需独立 nginx。非 /api、/ws 的未知路径一律回退 index.html(react-router 前端路由)。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/frontend/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 根路径与目录路径不返回目录资源(会产生 500),直接回 index.html
                        if (resourcePath.isEmpty() || resourcePath.endsWith("/")) {
                            Resource index = location.createRelative("index.html");
                            return index.exists() && index.isReadable() ? index : null;
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable() && !requested.toString().endsWith("/")) {
                            return requested;
                        }
                        // API/WS 走各自处理器,不做 SPA 回退
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws/")) {
                            return null;
                        }
                        Resource index = location.createRelative("index.html");
                        return index.exists() && index.isReadable() ? index : null;
                    }
                });
    }
}
