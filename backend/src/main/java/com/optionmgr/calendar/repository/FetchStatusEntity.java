package com.optionmgr.calendar.repository;

import com.optionmgr.calendar.domain.FetchResult;
import com.optionmgr.calendar.domain.FetchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** FEAT-009: the single row (id 1) of market_holiday_fetch_status (V13). */
@Entity
@Table(name = "market_holiday_fetch_status")
public class FetchStatusEntity {

    public static final short ID = 1;

    @Id
    private Short id;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "last_attempt_result", length = 20)
    private FetchResult lastAttemptResult;

    @Column(name = "last_failure_detail", length = 200)
    private String lastFailureDetail;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    protected FetchStatusEntity() {
    }

    public FetchStatus toDomain() {
        return new FetchStatus(lastAttemptAt, lastAttemptResult, lastFailureDetail, lastSuccessAt);
    }

    public void update(FetchStatus s) {
        this.lastAttemptAt = s.lastAttemptAt();
        this.lastAttemptResult = s.lastAttemptResult();
        String detail = s.lastFailureDetail();
        this.lastFailureDetail = detail != null && detail.length() > 200 ? detail.substring(0, 200) : detail;
        this.lastSuccessAt = s.lastSuccessAt();
    }
}
