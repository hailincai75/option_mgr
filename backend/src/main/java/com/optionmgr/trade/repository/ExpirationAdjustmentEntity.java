package com.optionmgr.trade.repository;

import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OptionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** FEAT-043: one expiration adjustment; the new contract (account + symbol + new date + strike + type) is unique. */
@Entity
@Immutable
@Table(name = "expiration_adjustment")
public class ExpirationAdjustmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 10)
    private String symbol;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal strike;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "option_type", nullable = false, length = 4)
    private OptionType optionType;

    @Column(name = "old_expiration_date", nullable = false)
    private LocalDate oldExpirationDate;

    @Column(name = "new_expiration_date", nullable = false)
    private LocalDate newExpirationDate;

    @Column(name = "adjusted_at", nullable = false)
    private Instant adjustedAt;

    @Column(name = "audit_record_id", nullable = false)
    private Long auditRecordId;

    protected ExpirationAdjustmentEntity() {
    }

    public ExpirationAdjustmentEntity(long accountId, ContractKey oldContract, LocalDate newExpirationDate,
                                      Instant adjustedAt, long auditRecordId) {
        this.accountId = accountId;
        this.symbol = oldContract.symbol();
        this.strike = oldContract.strike();
        this.optionType = oldContract.optionType();
        this.oldExpirationDate = oldContract.expirationDate();
        this.newExpirationDate = newExpirationDate;
        this.adjustedAt = adjustedAt;
        this.auditRecordId = auditRecordId;
    }

    /** The contract after the adjustment. */
    public ContractKey newContract() {
        return new ContractKey(symbol, newExpirationDate, strike, optionType);
    }

    public Long getId() {
        return id;
    }
}
