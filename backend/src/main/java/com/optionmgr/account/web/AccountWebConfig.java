package com.optionmgr.account.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the {@link AccountRef} and {@link AccountScope} (FEAT-035) argument resolvers. */
@Configuration
public class AccountWebConfig implements WebMvcConfigurer {

    static {
        // Both come from the X-Account-Id header, not from query parameters: hide them from the OpenAPI docs.
        org.springdoc.core.utils.SpringDocUtils.getConfig().addRequestWrapperToIgnore(AccountRef.class);
        org.springdoc.core.utils.SpringDocUtils.getConfig().addRequestWrapperToIgnore(AccountScope.class);
    }

    private final AccountRefArgumentResolver resolver;
    private final AccountScopeArgumentResolver scopeResolver;

    public AccountWebConfig(AccountRefArgumentResolver resolver, AccountScopeArgumentResolver scopeResolver) {
        this.resolver = resolver;
        this.scopeResolver = scopeResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
        resolvers.add(scopeResolver);
    }
}
