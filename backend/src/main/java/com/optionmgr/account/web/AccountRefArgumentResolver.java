package com.optionmgr.account.web;

import com.optionmgr.account.service.AccountService;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link AccountRef} from the {@code X-Account-Id} header (see docs/api/FEAT-033.md). FEAT-035: {@code ALL}
 * is rejected here — {@code ALL_ACCOUNTS_READ_ONLY} for writes, {@code ALL_ACCOUNTS_NOT_SUPPORTED} for reads —
 * because only the endpoints declaring {@link AccountScope} accept it; so every new write endpoint is protected.
 */
@Component
public class AccountRefArgumentResolver implements HandlerMethodArgumentResolver {

    static final String SELECT_ONE_ACCOUNT = "请选择一个账户";
    static final String READ_ONLY_MESSAGE = "全部账户模式只读：录入、修改、删除请先选择一个账户";
    static final String NOT_SUPPORTED_MESSAGE = "此接口暂不支持全部账户，请选择一个账户";

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
        String raw = requireHeader(webRequest);
        if (AccountScope.isAll(raw)) {
            HttpServletRequest http = webRequest.getNativeRequest(HttpServletRequest.class);
            String method = http == null ? "GET" : http.getMethod();
            boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
            throw new ApiException(read ? ErrorCode.ALL_ACCOUNTS_NOT_SUPPORTED : ErrorCode.ALL_ACCOUNTS_READ_ONLY,
                    read ? NOT_SUPPORTED_MESSAGE : READ_ONLY_MESSAGE,
                    List.of(new FieldError(AccountRef.HEADER, SELECT_ONE_ACCOUNT)));
        }
        return new AccountRef(existingId(raw, accounts));
    }

    /** FEAT-033: missing / blank header = {@code ACCOUNT_REQUIRED}; returns the raw value otherwise. */
    static String requireHeader(NativeWebRequest webRequest) {
        String raw = webRequest.getHeader(AccountRef.HEADER);
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.ACCOUNT_REQUIRED, "Account required",
                    List.of(new FieldError(AccountRef.HEADER, "请指定账户")));
        }
        return raw;
    }

    /** FEAT-033: a decimal id of an existing account ({@code BAD_REQUEST} / {@code ACCOUNT_NOT_FOUND} otherwise). */
    static long existingId(String raw, AccountService accounts) {
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
        return id;
    }
}
