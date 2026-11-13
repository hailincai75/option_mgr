package com.optionmgr.account.web;

import com.optionmgr.account.service.AccountService;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse.FieldError;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@link AccountRef} from the {@code X-Account-Id} header (see docs/api/api-contract.md FEAT-033). */
@Component
public class AccountRefArgumentResolver implements HandlerMethodArgumentResolver {

    private final AccountService accounts;

    public AccountRefArgumentResolver(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AccountRef.class;
    }

    @Override
    public AccountRef resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String raw = webRequest.getHeader(AccountRef.HEADER);
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.ACCOUNT_REQUIRED, "Account required",
                    List.of(new FieldError(AccountRef.HEADER, "请指定账户")));
        }
        long id;
        try {
            id = Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid parameter value",
                    List.of(new FieldError(AccountRef.HEADER, "账户 id 必须是整数")));
        }
        if (!accounts.exists(id)) {
            throw new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + id + " not found");
        }
        return new AccountRef(id);
    }
}
