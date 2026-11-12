package com.melonhub.rbac.config;

import com.melonhub.rbac.security.AdminFunctionGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {
    private final AdminFunctionGuard functions;
    public WebConfiguration(AdminFunctionGuard functions) { this.functions = functions; }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(functions).addPathPatterns("/api/admin", "/api/admin/**");
    }
}
