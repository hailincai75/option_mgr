package com.optionmgr.account.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the {@link AccountRef} argument resolver. */
@Configuration
public class AccountWebConfig implements WebMvcConfigurer {

    static {
        // AccountRef comes from the X-Account-Id header, not from query parameters: hide it from the OpenAPI docs.
        org.springdoc.core.utils.SpringDocUtils.getConfig().addRequestWrapperToIgnore(AccountRef.class);
    }

    private final AccountRefArgumentResolver resolver;

    public AccountWebConfig(AccountRefArgumentResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
    }
}
