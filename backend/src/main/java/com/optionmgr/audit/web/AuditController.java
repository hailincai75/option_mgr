package com.optionmgr.audit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.optionmgr.audit.domain.AuditObjectType;
import com.optionmgr.audit.domain.AuditOperation;
import com.optionmgr.audit.service.AuditService;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse.FieldError;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-005 audit log (read-only). */
@RestController
@RequestMapping("/api/audit-log")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    public record AuditRecordResponse(long id, Instant operatedAt, AuditOperation operation,
                                      AuditObjectType objectType, long objectId, String summary, JsonNode before,
                                      JsonNode after, JsonNode changes, JsonNode sideEffects) {
    }

    public record AuditPageResponse(List<AuditRecordResponse> items, int page, int size, long totalItems,
                                    int totalPages) {
    }

    @GetMapping
    public AuditPageResponse list(@RequestParam(required = false) AuditObjectType objectType,
                                  @RequestParam(required = false) Long objectId,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        List<FieldError> errors = new ArrayList<>();
        if (objectId != null && objectType == null) {
            errors.add(new FieldError("objectType", "请同时指定对象类型"));
        }
        if (page < 1) {
            errors.add(new FieldError("page", "page 必须 ≥ 1"));
        }
        if (size < 1 || size > 100) {
            errors.add(new FieldError("size", "size 必须在 1 到 100 之间"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed", errors);
        }
        AuditService.AuditPage p = audit.page(objectType, objectId, page, size);
        return new AuditPageResponse(p.items().stream().map(v -> new AuditRecordResponse(v.id(), v.operatedAt(),
                v.operation(), v.objectType(), v.objectId(), v.summary(), v.before(), v.after(), v.changes(),
                v.sideEffects())).toList(), p.page(), p.size(), p.totalItems(), p.totalPages());
    }
}
