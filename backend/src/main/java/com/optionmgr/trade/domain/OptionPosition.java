package com.optionmgr.trade.domain;

import com.optionmgr.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Current open position of one contract (FEAT-003): one direction (Q7), one multiplier (Q23), FIFO lots with
 * remaining contracts &gt; 0. Every figure is derived from the lots.
 */
public record OptionPosition(ContractKey contract, PositionDirection direction, int multiplier,
                             List<PositionLot> lots) {

    /** Q22: display-only average, rounded half-up to the cent. */
    public static final RoundingMode AVERAGE_ROUNDING = RoundingMode.HALF_UP;

    /** Q35: expiration date, symbol, strike (numeric), CALL before PUT. */
    public static final Comparator<OptionPosition> DISPLAY_ORDER = Comparator
            .comparing((OptionPosition p) -> p.contract().expirationDate())
            .thenComparing(p -> p.contract().symbol())
            .thenComparing(p -> p.contract().strike())
            .thenComparing(p -> p.contract().optionType());

    public OptionPosition {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(direction, "direction");
        lots = List.copyOf(lots);
        if (lots.isEmpty()) {
            throw new IllegalArgumentException("a position has at least one lot");
        }
    }

    /** Absolute net contracts = sum of remaining contracts. */
    public int contracts() {
        return lots.stream().mapToInt(PositionLot::remainingContracts).sum();
    }

    /** Sum of remaining opening net amounts (short +, long -). */
    public BigDecimal openingNetTotal() {
        return lots.stream().map(PositionLot::remainingNetAmount).reduce(Money.ZERO, BigDecimal::add);
    }

    /** openingNetTotal / contracts, half-up to the cent (Q22). */
    public BigDecimal averageNetPerContract() {
        return openingNetTotal().divide(BigDecimal.valueOf(contracts()), Money.SCALE, AVERAGE_ROUNDING);
    }

    /** Sum of the lots' remaining frozen cash (Q28: not recomputed from the total contracts). */
    public BigDecimal frozenCash() {
        return lots.stream().map(PositionLot::remainingFrozen).reduce(Money.ZERO, BigDecimal::add);
    }

    /** Calendar days from {@code today} (New York date) to expiration: 0 on the day, negative after. */
    public long dte(LocalDate today) {
        return ChronoUnit.DAYS.between(today, contract.expirationDate());
    }

    public ExpirationStatus expirationStatus(LocalDate today) {
        return ExpirationStatus.of(dte(today));
    }

    /** FEAT-004, tightened by FEAT-012 (Q83): a position can be closed before 16:00 New York on its expiration date. */
    public boolean closable(Instant now) {
        return ExpirationTimes.closable(contract.expirationDate(), now);
    }

    /** FEAT-012 (Q10): "到期待确认" between 16:00 and 17:30 New York on the expiration date. */
    public boolean expiryPending(Instant now) {
        return ExpirationTimes.expiryPending(contract.expirationDate(), now);
    }

    /** FEAT-012 (Q10): the position can be expired from 17:30 New York on its expiration date. */
    public boolean expirable(Instant now) {
        return ExpirationTimes.expirable(contract.expirationDate(), now);
    }

    /** FEAT-004: BTC closes a short position, STC a long one. */
    public TradeAction closeAction() {
        return direction == PositionDirection.SHORT ? TradeAction.BTC : TradeAction.STC;
    }

    /** Q21: allowed close sizes = cumulative contracts of the earliest 1, 2, ... whole lots. */
    public List<Integer> allowedCloseContracts() {
        return ContractReplay.cumulativeContracts(lots);
    }
}
