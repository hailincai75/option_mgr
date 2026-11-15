package com.optionmgr.account.web;

import com.optionmgr.account.repository.AccountEntity;
import com.optionmgr.account.service.AccountService;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * FEAT-035: resolves {@link AccountScope} from the {@code X-Account-Id} header. {@code ALL} (strip, any case) = every
 * account existing now, by id ascending (rule 2); otherwise the same checks as {@link AccountRef} (FEAT-033).
 */
@Component
public class AccountScopeArgumentResolver implements HandlerMethodArgumentResolver {

    private final AccountService accounts;

    public AccountScopeArgumentResolver(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AccountScope.class;
    }

    @Override
    public AccountScope resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                        NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String raw = AccountRefArgumentResolver.requireHeader(webRequest);
        if (AccountScope.isAll(raw)) {
            return new AccountScope(true, accounts.list().stream().map(AccountScopeArgumentResolver::member).toList());
        }
        long id = AccountRefArgumentResolver.existingId(raw, accounts);
        return new AccountScope(false, List.of(member(accounts.get(id))));
    }

    private static AccountScope.Member member(AccountEntity a) {
        return new AccountScope.Member(a.getId(), a.getName());
    }
}
