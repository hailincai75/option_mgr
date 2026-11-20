package com.optionmgr.trade.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * FEAT-051 (Q255): one lot (opening trade) closed by a BTC / STC - its stored selection. An open is selected by at
 * most one close (unique lot_trade_id). EXP / ASG / EXR have no rows (matched FIFO on read, Q260).
 */
@Entity
@Immutable
@IdClass(OptionCloseLotEntity.Key.class)
@Table(name = "option_close_lot")
public class OptionCloseLotEntity {

    @Id
    @Column(name = "close_trade_id", nullable = false)
    private Long closeTradeId;

    @Id
    @Column(name = "lot_trade_id", nullable = false)
    private Long lotTradeId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    protected OptionCloseLotEntity() {
    }

    public OptionCloseLotEntity(long closeTradeId, long lotTradeId, long accountId) {
        this.closeTradeId = closeTradeId;
        this.lotTradeId = lotTradeId;
        this.accountId = accountId;
    }

    public Long getCloseTradeId() {
        return closeTradeId;
    }

    public Long getLotTradeId() {
        return lotTradeId;
    }

    public Long getAccountId() {
        return accountId;
    }

    /** Composite primary key (close_trade_id, lot_trade_id). */
    public static class Key implements Serializable {
        private Long closeTradeId;
        private Long lotTradeId;

        public Key() {
        }

        public Key(Long closeTradeId, Long lotTradeId) {
            this.closeTradeId = closeTradeId;
            this.lotTradeId = lotTradeId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(closeTradeId, k.closeTradeId)
                    && Objects.equals(lotTradeId, k.lotTradeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(closeTradeId, lotTradeId);
        }
    }
}
