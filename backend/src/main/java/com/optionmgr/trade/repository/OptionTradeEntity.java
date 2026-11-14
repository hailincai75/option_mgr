package com.optionmgr.trade.repository;

import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.CoverageStatus;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.TradeAction;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One option trade as entered by the user. FEAT-001 stores opens (BTO / STO); FEAT-004 will store closes
 * (BTC / STC) here as well. {@code id} is the entry order; position replay order is (tradedAt, id).
 * The cash effect is the cash_ledger_entry with source OPTION_OPEN / id.
 */
@Entity
@Table(name = "option_trade")
public class OptionTradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 10)
    private String symbol;

    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal strike;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "option_type", nullable = false, length = 4)
    private OptionType optionType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 3)
    private TradeAction action;

    @Column(nullable = false)
    private int contracts;

    @Column(nullable = false)
    private int multiplier;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "traded_at", nullable = false)
    private Instant tradedAt;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** True once the trade itself has been edited (it has an audit record); never set by cascade corrections. */
    @Column(name = "has_audit_log", nullable = false)
    private boolean hasAuditLog;

    /**
     * FEAT-013: assignment fee of an ASG record (&gt;= 0); FEAT-014: the exercise fee of an EXR record (same column,
     * V12); null for every other action.
     */
    @Column(name = "assignment_fee", precision = 19, scale = 2)
    private BigDecimal assignmentFee;

    /** FEAT-039: CHECKED / LEGACY for an STO call lot; null for every other record. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "coverage_status", length = 8)
    private CoverageStatus coverageStatus;

    /**
     * FEAT-039: entry order shared with stock_trade (database sequence, set on insert). Null on an entity inserted in
     * the current transaction: such a record was entered last.
     */
    @Column(name = "entry_seq", insertable = false, updatable = false)
    private Long entrySeq;

    protected OptionTradeEntity() {
    }

    public CoverageStatus getCoverageStatus() {
        return coverageStatus;
    }

    /** FEAT-039: a legacy lot passed the coverage check (edit or automatic inclusion); never goes back. */
    public void markCoverageChecked() {
        if (coverageStatus == null) {
            throw new IllegalStateException("not an STO call: " + id);
        }
        this.coverageStatus = CoverageStatus.CHECKED;
    }

    /** Test support only (e2e): an STO call entered as if before FEAT-039. */
    public void markCoverageLegacy() {
        if (coverageStatus == null) {
            throw new IllegalStateException("not an STO call: " + id);
        }
        this.coverageStatus = CoverageStatus.LEGACY;
    }

    /** Entry order across option and stock records; {@link Long#MAX_VALUE} for a record inserted just now. */
    public long entrySeq() {
        return entrySeq == null ? Long.MAX_VALUE : entrySeq;
    }

    /** FEAT-013: an assignment record (ASG, net amount 0) with its fee. */
    public static OptionTradeEntity assignment(long accountId, ContractKey contract, int contracts, int multiplier,
                                               BigDecimal fee, Instant assignedAt, String note, Instant createdAt) {
        OptionTradeEntity e = new OptionTradeEntity(accountId, contract, TradeAction.ASG, contracts, multiplier,
                BigDecimal.ZERO.setScale(2), assignedAt, note, createdAt);
        e.assignmentFee = fee;
        return e;
    }

    /** FEAT-014: an exercise record (EXR, net amount 0) with its fee. */
    public static OptionTradeEntity exercise(long accountId, ContractKey contract, int contracts, int multiplier,
                                             BigDecimal fee, Instant exercisedAt, String note, Instant createdAt) {
        OptionTradeEntity e = new OptionTradeEntity(accountId, contract, TradeAction.EXR, contracts, multiplier,
                BigDecimal.ZERO.setScale(2), exercisedAt, note, createdAt);
        e.assignmentFee = fee;
        return e;
    }

    /** The fee of an ASG (assignment fee) or (FEAT-014) EXR (exercise fee) record; null for other actions. */
    public BigDecimal getAssignmentFee() {
        return assignmentFee;
    }

    public OptionTradeEntity(long accountId, ContractKey contract, TradeAction action, int contracts, int multiplier,
                             BigDecimal netAmount, Instant tradedAt, String note, Instant createdAt) {
        this.accountId = accountId;
        this.symbol = contract.symbol();
        this.expirationDate = contract.expirationDate();
        this.strike = contract.strike();
        this.optionType = contract.optionType();
        this.action = action;
        this.contracts = contracts;
        this.multiplier = multiplier;
        this.netAmount = netAmount;
        this.tradedAt = tradedAt;
        this.note = note;
        this.createdAt = createdAt;
        this.coverageStatus = action == TradeAction.STO && contract.optionType() == OptionType.CALL
                ? CoverageStatus.CHECKED : null;
    }

    /** FEAT-005 edit (Q43): only these fields change; contract and action never do. The id (entry order) stays. */
    public void update(int contracts, int multiplier, BigDecimal netAmount, Instant tradedAt, String note) {
        this.contracts = contracts;
        this.multiplier = multiplier;
        this.netAmount = netAmount;
        this.tradedAt = tradedAt;
        this.note = note;
    }

    /** Called only by the edit path, in the transaction that writes this trade's UPDATE audit record. */
    public void markAudited() {
        this.hasAuditLog = true;
    }

    public boolean hasAuditLog() {
        return hasAuditLog;
    }

    public ContractKey contract() {
        return new ContractKey(symbol, expirationDate, strike, optionType);
    }

    public Long getId() {
        return id;
    }

    public long getAccountId() {
        return accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public BigDecimal getStrike() {
        return strike;
    }

    public OptionType getOptionType() {
        return optionType;
    }

    public TradeAction getAction() {
        return action;
    }

    public int getContracts() {
        return contracts;
    }

    public int getMultiplier() {
        return multiplier;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public Instant getTradedAt() {
        return tradedAt;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
