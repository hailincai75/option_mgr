package com.optionmgr.common.error;

import org.springframework.http.HttpStatus;

/**
 * Generic error codes shared by all endpoints. Each code maps to exactly one HTTP status.
 * Keep in sync with the table in docs/api/api-contract.md.
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    CONFLICT(HttpStatus.CONFLICT),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-007 (and later trades): a cash movement would make total or available cash negative at some point. */
    CASH_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-001 Q7: an opening trade goes against the contract's long/short position. */
    POSITION_DIRECTION_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-001 Q23: multiplier differs from the open position of the same contract. */
    MULTIPLIER_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-004: a close finds no position (at its time, or a later close after a back-fill). */
    NO_POSITION_TO_CLOSE(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-004: close contracts exceed the position. */
    CLOSE_EXCEEDS_POSITION(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-004 Q21: close contracts are not the earliest whole lots. */
    CLOSE_NOT_WHOLE_LOTS(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-005: an edit that changes nothing ("没有修改"); nothing saved, nothing audited. */
    NO_CHANGES(HttpStatus.UNPROCESSABLE_ENTITY),
    /** FEAT-033: an account-scoped endpoint was called without the X-Account-Id header. */
    ACCOUNT_REQUIRED(HttpStatus.BAD_REQUEST),
    /** FEAT-033: the X-Account-Id / path account does not exist. */
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
