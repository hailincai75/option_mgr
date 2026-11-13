package com.optionmgr.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optionmgr.audit.domain.AuditObjectType;
import com.optionmgr.audit.domain.AuditOperation;
import com.optionmgr.audit.domain.FieldChange;
import com.optionmgr.audit.domain.SideEffect;
import com.optionmgr.audit.repository.AuditRecordEntity;
import com.optionmgr.audit.repository.AuditRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-005 audit log: append-only records of edits and deletions (Q12, Q42, Q44). */
@Service
public class AuditService {

    static final Sort ORDER = Sort.by(Sort.Order.desc("operatedAt"), Sort.Order.desc("id"));

    private final AuditRecordRepository records;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditService(AuditRecordRepository records, ObjectMapper json, Clock clock) {
        this.records = records;
        this.json = json;
        this.clock = clock;
    }

    /**
     * An audit record to write.
     *
     * @param accountId account the record belongs to (FEAT-033)
     * @param before    full content before (JSON-serializable, e.g. a LinkedHashMap); null for a creation
     * @param after  full content after; null for a deletion
     */
    public record NewAuditRecord(long accountId, AuditOperation operation, AuditObjectType objectType, long objectId, String summary,
                                 Object before, Object after, List<FieldChange> changes,
                                 List<SideEffect> sideEffects) {
        public NewAuditRecord {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(objectType, "objectType");
            changes = List.copyOf(changes);
            sideEffects = List.copyOf(sideEffects);
        }
    }

    /** A stored record with its JSON parsed back. */
    public record AuditView(long id, Instant operatedAt, AuditOperation operation, AuditObjectType objectType,
                            long objectId, String summary, JsonNode before, JsonNode after, JsonNode changes,
                            JsonNode sideEffects) {
    }

    public record AuditPage(List<AuditView> items, int page, int size, long totalItems, int totalPages) {
    }

    /** Must join the caller's transaction: the audit record is written together with the change it describes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long record(NewAuditRecord r) {
        List<Map<String, Object>> changes = r.changes().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", c.field());
            m.put("label", c.label());
            m.put("before", c.before());
            m.put("after", c.after());
            m.put("text", c.text());
            return m;
        }).toList();
        List<Map<String, Object>> effects = r.sideEffects().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradeId", e.tradeId());
            m.put("action", e.action());
            m.put("contractLabel", e.contractLabel());
            m.put("field", e.field());
            m.put("before", e.before());
            m.put("after", e.after());
            m.put("text", e.text());
            return m;
        }).toList();
        AuditRecordEntity saved = records.save(new AuditRecordEntity(r.accountId(), clock.instant(), r.operation(),
                r.objectType(), r.objectId(), r.summary(), r.before() == null ? null : write(r.before()), r.after() == null ? null : write(r.after()),
                write(changes), write(effects)));
        return saved.getId();
    }

    /** Newest first (same instant: higher id first); optional filter by object type and id. */
    @Transactional(readOnly = true)
    public AuditPage page(long accountId, AuditObjectType objectType, Long objectId, int page, int size) {
        PageRequest request = PageRequest.of(page - 1, size, ORDER);
        Page<AuditRecordEntity> result;
        if (objectType == null) {
            result = records.findByAccountId(accountId, request);
        } else if (objectId == null) {
            result = records.findByAccountIdAndObjectType(accountId, objectType, request);
        } else {
            result = records.findByAccountIdAndObjectTypeAndObjectId(accountId, objectType, objectId, request);
        }
        return new AuditPage(result.getContent().stream().map(this::view).toList(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    private AuditView view(AuditRecordEntity e) {
        return new AuditView(e.getId(), e.getOperatedAt(), e.getOperation(), e.getObjectType(), e.getObjectId(),
                e.getSummary(), e.getBeforeJson() == null ? null : read(e.getBeforeJson()), e.getAfterJson() == null ? null : read(e.getAfterJson()),
                read(e.getChangesJson()), read(e.getSideEffectsJson()));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize audit data", e);
        }
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt audit data", e);
        }
    }
}
