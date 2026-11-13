package com.optionmgr.trade.repository;

import com.optionmgr.trade.domain.ContractKey;
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

    protected OptionTradeEntity() {
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
    }

    /** FEAT-005 edit (Q43): only these fields change; contract and action never do. The id (entry order) stays. */
    public void update(int contracts, int multiplier, BigDecimal netAmount, Instant tradedAt, String note) {
        this.contracts = contracts;
        this.multiplier = multiplier;
        this.netAmount = netAmount;
        this.tradedAt = tradedAt;
        this.note = note;
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
