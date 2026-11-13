package com.optionmgr.trade.service;

import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.LotTrade;
import com.optionmgr.trade.domain.OptionPosition;
import com.optionmgr.trade.domain.PositionBuilder;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-003: current option positions, derived from the trades on every read (read-only). */
@Service
public class OptionPositionService {

    private final OptionTradeRepository trades;
    private final CashLedgerService ledger;
    private final Clock clock;

    public OptionPositionService(OptionTradeRepository trades, CashLedgerService ledger, Clock clock) {
        this.trades = trades;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @param asOfDate     "today" = New York date of the server clock; DTE is relative to it
     * @param positions    sorted for display (Q35)
     * @param frozenTotal  sum of the positions' frozen cash
     */
    public record PositionSummary(LocalDate asOfDate, List<OptionPosition> positions, BigDecimal frozenTotal) {
    }

    @Transactional(readOnly = true)
    public PositionSummary currentPositions(long accountId) {
        LocalDate today = MarketTime.toLocal(clock.instant()).toLocalDate();
        List<OptionTradeEntity> all = trades.findByAccountIdOrderByTradedAtAscIdAsc(accountId);
        Map<Long, CashLedgerEntryEntity> entries = ledger.findBySources(accountId, OptionTradeService.TRADE_SOURCE_TYPES,
                all.stream().map(OptionTradeEntity::getId).toList());

        Map<ContractKey, List<LotTrade>> byContract = new LinkedHashMap<>();
        for (OptionTradeEntity t : all) {
            CashLedgerEntryEntity e = entries.get(t.getId());
            if (e == null) {
                throw new IllegalStateException("ledger entry missing for option trade " + t.getId());
            }
            byContract.computeIfAbsent(t.contract(), k -> new ArrayList<>()).add(OptionTradeService.toLotTrade(t, e));
        }

        List<OptionPosition> positions = byContract.entrySet().stream()
                .map(en -> PositionBuilder.build(en.getKey(), en.getValue()))
                .flatMap(Optional::stream)
                .sorted(OptionPosition.DISPLAY_ORDER)
                .toList();
        BigDecimal frozenTotal = positions.stream().map(OptionPosition::frozenCash)
                .reduce(Money.ZERO, BigDecimal::add);
        return new PositionSummary(today, positions, frozenTotal);
    }
}
