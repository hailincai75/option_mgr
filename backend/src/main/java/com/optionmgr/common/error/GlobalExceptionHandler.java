package com.optionmgr.common.error;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every exception escaping a controller to the unified {@link ErrorResponse}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String VALIDATION_FAILED = "Request validation failed";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest req) {
        return build(ex.code(), ex.getMessage(), ex.details(), req);
    }

    /** {@code @Valid} on {@code @RequestBody}/{@code @ModelAttribute}; MethodArgumentNotValidException extends BindException. */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBind(BindException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> details = ex.getBindingResult().getAllErrors().stream()
                .map(e -> new ErrorResponse.FieldError(
                        e instanceof FieldError fe ? fe.getField() : e.getObjectName(),
                        e.getDefaultMessage()))
                .sorted(byField())
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, VALIDATION_FAILED, details, req);
    }

    /** Constraint annotations directly on controller method parameters (Spring 6.1+ method validation). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex,
                                                                HttpServletRequest req) {
        List<ErrorResponse.FieldError> details = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(e -> new ErrorResponse.FieldError(
                        r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .sorted(byField())
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, VALIDATION_FAILED, details, req);
    }

    /** Bean validation triggered outside MVC binding, e.g. {@code @Validated} services. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest req) {
        List<ErrorResponse.FieldError> details = ex.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .sorted(byField())
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, VALIDATION_FAILED, details, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest req) {
        // A well-formed JSON body whose field value cannot be converted (bad number/enum/date): name the field.
        if (ex.getCause() instanceof MismatchedInputException mie && !mie.getPath().isEmpty()) {
            String field = jsonPath(mie.getPath());
            return build(ErrorCode.BAD_REQUEST, "Malformed or unreadable request body",
                    List.of(new ErrorResponse.FieldError(field, "has an invalid value or format")), req);
        }
        return build(ErrorCode.BAD_REQUEST, "Malformed or unreadable request body", List.of(), req);
    }

    private static String jsonPath(List<JsonMappingException.Reference> path) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : path) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest req) {
        String expected = ex.getRequiredType() == null ? "value" : ex.getRequiredType().getSimpleName();
        return build(ErrorCode.BAD_REQUEST, "Invalid parameter value",
                List.of(new ErrorResponse.FieldError(ex.getName(), "must be a valid " + expected)), req);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest req) {
        return build(ErrorCode.BAD_REQUEST, "Missing required parameter",
                List.of(new ErrorResponse.FieldError(ex.getParameterName(), "is required")), req);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoRoute(Exception ex, HttpServletRequest req) {
        return build(ErrorCode.NOT_FOUND, "Resource not found", List.of(), req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex,
                                                      HttpServletRequest req) {
        return build(ErrorCode.METHOD_NOT_ALLOWED,
                "HTTP method " + ex.getMethod() + " is not supported for this path", List.of(), req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest req) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported; use application/json",
                List.of(), req);
    }

    /** Other Spring MVC exceptions that carry their own status (e.g. ResponseStatusException). */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> handleSpringErrorResponse(ErrorResponseException ex,
                                                                   HttpServletRequest req) {
        ErrorCode code = fromStatus(ex.getStatusCode());
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
            return build(code, "Internal server error", List.of(), req);
        }
        return build(code, code.status().getReasonPhrase(), List.of(), req);
    }

    /** Last resort: log the full stack trace server-side, never expose it to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, "Internal server error", List.of(), req);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message,
                                                List<ErrorResponse.FieldError> details, HttpServletRequest req) {
        ErrorResponse body = new ErrorResponse(code.name(), message, code.status().value(),
                req.getRequestURI(), clock.instant(), details);
        return ResponseEntity.status(code.status()).body(body);
    }

    private static ErrorCode fromStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.BAD_REQUEST;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> ErrorCode.CONFLICT;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> ErrorCode.BUSINESS_RULE_VIOLATION;
            default -> status.is4xxClientError() ? ErrorCode.BAD_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
    }

    private static Comparator<ErrorResponse.FieldError> byField() {
        return Comparator.comparing(ErrorResponse.FieldError::field, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ErrorResponse.FieldError::message, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    private static String lastNode(String path) {
        int i = path.lastIndexOf('.');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
