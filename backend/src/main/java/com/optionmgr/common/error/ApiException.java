package com.optionmgr.common.error;

import java.util.List;

/**
 * Throw from service/controller code to produce a well-formed error response.
 * The message is shown to API clients, so it must not contain internal details.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<ErrorResponse.FieldError> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<ErrorResponse.FieldError> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException businessRule(String message) {
        return new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    public ErrorCode code() {
        return code;
    }

    public List<ErrorResponse.FieldError> details() {
        return details;
    }
}
