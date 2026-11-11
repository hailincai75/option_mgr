package com.optionmgr.common.error;

import java.time.Instant;
import java.util.List;

/** The single error body returned by every /api endpoint on failure. */
public record ErrorResponse(
        String code,
        String message,
        int status,
        String path,
        Instant timestamp,
        List<FieldError> details) {

    public ErrorResponse {
        details = details == null ? List.of() : List.copyOf(details);
    }

    /** One problem with one input field (request body property, query or path parameter). */
    public record FieldError(String field, String message) {
    }
}
