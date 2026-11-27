package com.firstfood.common.config;

import com.firstfood.identity.security.CustomerPortalInterceptor;
import com.firstfood.identity.security.StaffPortalInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final StaffPortalInterceptor staffPortalInterceptor;

    private final CustomerPortalInterceptor customerPortalInterceptor;

    public WebConfig(
            StaffPortalInterceptor staffPortalInterceptor, CustomerPortalInterceptor customerPortalInterceptor) {
        this.staffPortalInterceptor = staffPortalInterceptor;
        this.customerPortalInterceptor = customerPortalInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Which routes belong to which door lives in PortalPaths, where PortalRouteCoverageTest checks that every
        // /api/** route is covered by exactly one list (F-14).
        registry.addInterceptor(staffPortalInterceptor).addPathPatterns(PortalPaths.STAFF);
        registry.addInterceptor(customerPortalInterceptor).addPathPatterns(PortalPaths.CUSTOMER);
    }
}
