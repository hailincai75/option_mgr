package com.optionmgr.trade.service;

import com.optionmgr.calendar.domain.AdhocClosureAlerts;
import com.optionmgr.calendar.domain.TradingCalendar;
import com.optionmgr.calendar.service.AdhocClosureService;
import com.optionmgr.calendar.service.TradingCalendarService;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.domain.StockPosition;
import com.optionmgr.stock.service.StockPositionService;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.CoverageStatus;
import com.optionmgr.trade.domain.CoverageTimeline;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionDirection;
import com.optionmgr.trade.domain.PositionLot;
import com.optionmgr.trade.domain.ExpiryReplay;
import com.optionmgr.trade.domain.LotTrade;
import com.optionmgr.trade.domain.OptionPosition;
import com.optionmgr.trade.domain.PositionBuilder;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final StockPositionService stockPositions;
    private final CoverageService coverage;
    private final TradingCalendarService tradingCalendar;
    private final AdhocClosureService adhocClosures;
    private final Clock clock;

    public OptionPositionService(OptionTradeRepository trades, CashLedgerService ledger,
                                 StockPositionService stockPositions, CoverageService coverage,
                                 TradingCalendarService tradingCalendar, AdhocClosureService adhocClosures,
                                 Clock clock) {
        this.adhocClosures = adhocClosures;
        this.tradingCalendar = tradingCalendar;
        this.coverage = coverage;
        this.stockPositions = stockPositions;
        this.trades = trades;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @param now             server clock: closable / expiry pending / expirable are relative to it (FEAT-012)
     * @param asOfDate        "today" = New York date of the server clock; DTE is relative to it
     * @param positions       sorted for display (Q35)
     * @param frozenTotal     sum of the positions' frozen cash
     * @param expiryContracts per position: contracts at the expiration cutoff (FEAT-012 expiry size)
     * @param stockShares     FEAT-041: current shares per symbol (symbols with shares &gt; 0)
     */
    public record PositionSummary(Instant now, LocalDate asOfDate, List<OptionPosition> positions,
                                  BigDecimal frozenTotal, Map<ContractKey, Integer> expiryContracts,
                                  Map<String, Long> stockShares, Map<ContractKey, RowCoverage> coverage,
                                  BigDecimal availableCash, Map<String, Long> availableShares,
                                  TradingCalendar calendar, Map<LocalDate, String> adhocClosures) {

        /** FEAT-042 rule 3/5: eventName of the ACTIVE ad-hoc closure the position expires on; null when none. */
        public String adhocClosureEventName(OptionPosition p) {
            return adhocClosures.get(p.contract().expirationDate());
        }

        /** FEAT-042 rule 4: alerts of this account (same data as the row tags). */
        public List<AdhocClosureAlerts.Alert> adhocClosureAlerts() {
            return AdhocClosureAlerts.alerts(adhocClosures,
                    positions.stream().map(p -> p.contract().expirationDate()).toList());
        }

        /** FEAT-009 Q125: trading days in (asOfDate, expiration]; 0 on the expiration date, null once expired. */
        public Integer remainingTradingDays(OptionPosition p) {
            return calendar.remainingTradingDays(asOfDate, p.contract().expirationDate());
        }

        /** FEAT-014: current available shares (held - covered by CHECKED short calls) of a long put's underlying. */
        public long availableShares(OptionPosition p) {
            return availableShares.getOrDefault(p.contract().symbol(), 0L);
        }

        /** FEAT-039: coverage of a short call row; null for every other row. */
        public RowCoverage coverage(OptionPosition p) {
            return coverage.get(p.contract());
        }

        public int expiryContracts(OptionPosition p) {
            return expiryContracts.getOrDefault(p.contract(), 0);
        }

        /** FEAT-041: current shares of the position's underlying (0 when none). */
        public long underlyingShares(OptionPosition p) {
            return stockShares.getOrDefault(p.contract().symbol(), 0L);
        }
    }

    /**
     * FEAT-039: one STO call lot of a short call row.
     *
     * @param requiredShares remaining contracts x multiplier
     * @param shortfall      LEGACY only: first point where it would not be covered (hover); null for CHECKED
     */
    public record LotCoverage(CoverageStatus status, long requiredShares, CoverageTimeline.Shortfall shortfall) {
    }

    /**
     * FEAT-039: a short call row: shares of its CHECKED lots, contracts / shares of its LEGACY lots, the symbol's
     * available shares now and the earliest LEGACY shortfall (null without LEGACY lots).
     */
    public record RowCoverage(long coveredShares, int uncoveredContracts, long uncoveredShares, long availableShares,
                              CoverageTimeline.Shortfall shortfall, Map<Long, LotCoverage> lots) {
    }

    @Transactional(readOnly = true)
    public PositionSummary currentPositions(long accountId) {
        Instant now = clock.instant();
        LocalDate today = MarketTime.toLocal(now).toLocalDate();
        List<OptionTradeEntity> all = trades.findByAccountIdOrderByTradedAtAscIdAsc(accountId);
        Map<Long, CashLedgerEntryEntity> entries = ledger.findBySources(accountId, OptionTradeService.TRADE_SOURCE_TYPES,
                all.stream().map(OptionTradeEntity::getId).toList());

        Map<ContractKey, List<LotTrade>> byContract = new LinkedHashMap<>();
        for (OptionTradeEntity t : all) {
            CashLedgerEntryEntity e = OptionTradeService.entry(t, entries);
            byContract.computeIfAbsent(t.contract(), k -> new ArrayList<>()).add(OptionTradeService.toLotTrade(t, e));
        }

        List<OptionPosition> positions = byContract.entrySet().stream()
                .map(en -> PositionBuilder.build(en.getKey(), en.getValue()))
                .flatMap(Optional::stream)
                .sorted(OptionPosition.DISPLAY_ORDER)
                .toList();
        Map<ContractKey, Integer> expiryContracts = new HashMap<>();
        for (OptionPosition p : positions) {
            expiryContracts.put(p.contract(), ExpiryReplay.contractsAtCutoff(p.contract(), byContract.get(p.contract())));
        }
        BigDecimal frozenTotal = positions.stream().map(OptionPosition::frozenCash)
                .reduce(Money.ZERO, BigDecimal::add);
        Map<String, Long> stockShares = new HashMap<>();
        for (StockPosition sp : stockPositions.currentPositions(accountId)) {
            stockShares.put(sp.symbol(), sp.shares());
        }
        Map<ContractKey, RowCoverage> rows = new HashMap<>();
        Map<String, CoverageService.Snapshot> snapshots = new HashMap<>();
        Map<String, Long> availableShares = new HashMap<>();
        for (OptionPosition p : positions) {
            if (p.direction() == PositionDirection.SHORT && p.contract().optionType() == OptionType.CALL) {
                CoverageService.Snapshot snapshot = snapshots.computeIfAbsent(p.contract().symbol(),
                        sym -> coverage.snapshot(accountId, sym));
                rows.put(p.contract(), rowCoverage(p, snapshot.timeline()));
            }
            if (p.direction() == PositionDirection.LONG && p.contract().optionType() == OptionType.PUT) {
                // FEAT-014: what a put exercise could deliver now (Q113)
                CoverageService.Snapshot snapshot = snapshots.computeIfAbsent(p.contract().symbol(),
                        sym -> coverage.snapshot(accountId, sym));
                availableShares.put(p.contract().symbol(),
                        Math.max(0, snapshot.timeline().current(CoverageTimeline.checked()).available()));
            }
        }
        BigDecimal availableCash = ledger.currentBalance(accountId).available();
        return new PositionSummary(now, today, positions, frozenTotal, Map.copyOf(expiryContracts),
                Map.copyOf(stockShares), Map.copyOf(rows), availableCash, Map.copyOf(availableShares), tradingCalendar.calendar(),
                Map.copyOf(adhocClosures.activeClosures()));
    }

    static RowCoverage rowCoverage(OptionPosition p, CoverageTimeline timeline) {
        long available = timeline.current(CoverageTimeline.checked()).available();
        long covered = 0;
        int uncoveredContracts = 0;
        long uncoveredShares = 0;
        CoverageTimeline.Shortfall earliest = null;
        Map<Long, LotCoverage> lots = new LinkedHashMap<>();
        for (PositionLot l : p.lots()) {
            long required = (long) l.remainingContracts() * l.multiplier();
            boolean checked = timeline.lot(l.tradeId()).map(CoverageTimeline.CallLot::checked).orElse(true);
            if (checked) {
                covered += required;
                lots.put(l.tradeId(), new LotCoverage(CoverageStatus.CHECKED, required, null));
                continue;
            }
            CoverageTimeline.Shortfall s = timeline.legacyShortfall(timeline.lot(l.tradeId()).orElseThrow())
                    .orElse(null);
            uncoveredContracts += l.remainingContracts();
            uncoveredShares += required;
            if (s != null && (earliest == null || s.at().isBefore(earliest.at()))) {
                earliest = s;
            }
            lots.put(l.tradeId(), new LotCoverage(CoverageStatus.LEGACY, required, s));
        }
        return new RowCoverage(covered, uncoveredContracts, uncoveredShares, available, earliest, lots);
    }
}
