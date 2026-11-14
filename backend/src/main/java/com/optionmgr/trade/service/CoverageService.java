package com.optionmgr.trade.service;

import com.optionmgr.audit.domain.SideEffect;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.money.Money;
import com.optionmgr.stock.domain.StockRecord;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.repository.StockTradeRepository;
import com.optionmgr.trade.domain.CloseMatch;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.ContractReplay;
import com.optionmgr.trade.domain.CoverageMessages;
import com.optionmgr.trade.domain.CoverageStatus;
import com.optionmgr.trade.domain.CoverageTimeline;
import com.optionmgr.trade.domain.CoverageTimeline.CallLot;
import com.optionmgr.trade.domain.CoverageTimeline.Key;
import com.optionmgr.trade.domain.CoverageTimeline.ShareMove;
import com.optionmgr.trade.domain.LotTrade;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionLot;
import com.optionmgr.trade.domain.ReplayedTrade;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-039: covered calls. Builds the {@link CoverageTimeline} of a symbol (stored records, optionally with a
 * proposed change applied), runs the checks E1 / E2 / E3 in the order of each write and the automatic inclusion of
 * LEGACY lots (Q101) after it. Callers hold the account lock.
 */
@Service
public class CoverageService {

    public static final String BEFORE_INCLUSION = "未覆盖";
    public static final String AFTER_INCLUSION = "已覆盖";

    private final OptionTradeRepository optionTrades;
    private final StockTradeRepository stockTrades;

    public CoverageService(OptionTradeRepository optionTrades, StockTradeRepository stockTrades) {
        this.optionTrades = optionTrades;
        this.stockTrades = stockTrades;
    }

    /**
     * A change to check, applied to the stored records of one symbol.
     *
     * @param stockChange       the symbol's stock records after the change (identity: unchanged)
     * @param contract          a call contract whose trades are replaced by {@code contractTrades}; null: none
     * @param contractTrades    that contract's trades after the change (ids = entry order; NOT_YET_ENTERED = new)
     * @param treatedChecked    STO call ids counted as CHECKED (a new lot is always CHECKED; an edited LEGACY lot)
     * @param newCallAssignment the new ASG (NOT_YET_ENTERED) delivers the new ASSIGN_SELL (NOT_YET_ENTERED)
     */
    public record Proposal(UnaryOperator<List<StockRecord>> stockChange, ContractKey contract,
                           List<LotTrade> contractTrades, Set<Long> treatedChecked, boolean newCallAssignment) {

        public Proposal {
            Objects.requireNonNull(stockChange, "stockChange");
            treatedChecked = Set.copyOf(treatedChecked);
        }

        public static Proposal none() {
            return new Proposal(UnaryOperator.identity(), null, null, Set.of(), false);
        }

        public static Proposal stock(UnaryOperator<List<StockRecord>> change) {
            return new Proposal(change, null, null, Set.of(), false);
        }

        /** Option trades of a contract replaced; ignored unless it is a call contract. */
        public static Proposal contract(ContractKey contract, List<LotTrade> trades) {
            return new Proposal(UnaryOperator.identity(), contract, List.copyOf(trades), Set.of(), false);
        }

        public Proposal withStock(UnaryOperator<List<StockRecord>> change) {
            return new Proposal(change, contract, contractTrades, treatedChecked, newCallAssignment);
        }

        public Proposal treatingChecked(long tradeId) {
            Set<Long> ids = new HashSet<>(treatedChecked);
            ids.add(tradeId);
            return new Proposal(stockChange, contract, contractTrades, ids, newCallAssignment);
        }

        public Proposal withNewCallAssignment() {
            return new Proposal(stockChange, contract, contractTrades, treatedChecked, true);
        }
    }

    /** A timeline plus what is needed to locate stock records in it. */
    public record Snapshot(String symbol, CoverageTimeline timeline, Map<Long, Key> stockKeys) {
    }

    // ------------------------------------------------------------------ checks

    /**
     * E1 for an STO call lot ({@code lotId}; NOT_YET_ENTERED for a new one): shares available right before it (other
     * CHECKED lots only) must be at least its required shares.
     */
    public void checkCallOpen(Snapshot s, long lotId) {
        CallLot lot = s.timeline().lot(lotId).orElseThrow(() -> new IllegalArgumentException("lot " + lotId));
        CoverageTimeline.State before = s.timeline().before(lot.opened(), CoverageTimeline.checkedExcept(lotId));
        if (before.available() < lot.requiredShares()) {
            throw new ApiException(ErrorCode.COVERAGE_INSUFFICIENT,
                    CoverageMessages.callNotCovered(lot.requiredShares(), s.symbol(), before));
        }
    }

    /** E2 for a stock sale ({@code stockId}): its shares must not exceed the shares available right before it. */
    public void checkSale(Snapshot s, long stockId, long shares) {
        Key key = s.stockKeys().get(stockId);
        if (key == null) {
            throw new IllegalArgumentException("stock record " + stockId + " not in snapshot");
        }
        CoverageTimeline.State before = s.timeline().before(key, CoverageTimeline.checked());
        if (shares > before.available()) {
            throw new ApiException(ErrorCode.COVERAGE_INSUFFICIENT,
                    CoverageMessages.saleOfCoveredShares(shares, before));
        }
    }

    /** E3: from {@code from} on, held &gt;= covered at every point; {@code verb} = 补录 / 修改 / 删除 / 被指派. */
    public void checkInvariant(Snapshot s, Instant from, String verb) {
        s.timeline().firstShortfall(CoverageTimeline.checked(), from).ifPresent(sf -> {
            throw new ApiException(ErrorCode.COVERAGE_INSUFFICIENT, CoverageMessages.changeUncovers(verb, s.symbol(), sf));
        });
    }

    // ------------------------------------------------------------------ automatic inclusion (Q101)

    /**
     * Includes the open LEGACY lots of a symbol that are now covered (Q101) and returns them as audit side effects
     * ("STO #5 纳入覆盖检查"); the caller writes them only for an edit / delete. Call after the write is applied.
     */
    @Transactional
    public List<SideEffect> includeLegacy(long accountId, String symbol) {
        List<OptionTradeEntity> calls = optionTrades.findByAccountIdAndSymbolAndOptionTypeOrderByTradedAtAscIdAsc(
                accountId, symbol, OptionType.CALL);
        if (calls.stream().noneMatch(t -> t.getCoverageStatus() == CoverageStatus.LEGACY)) {
            return List.of();
        }
        CoverageTimeline timeline = snapshot(accountId, symbol, Proposal.none(), calls).timeline();
        List<SideEffect> effects = new ArrayList<>();
        Map<Long, OptionTradeEntity> byId = new HashMap<>();
        calls.forEach(t -> byId.put(t.getId(), t));
        for (long id : timeline.inclusions()) {
            OptionTradeEntity lot = byId.get(id);
            lot.markCoverageChecked();
            effects.add(inclusionEffect(lot));
        }
        return effects;
    }

    /** At startup: inclusion for every account and symbol that has LEGACY lots. No audit. */
    @Transactional
    public void includeLegacyEverywhere() {
        Set<Map.Entry<Long, String>> keys = new LinkedHashSet<>();
        for (OptionTradeEntity t : optionTrades.findByCoverageStatus(CoverageStatus.LEGACY)) {
            keys.add(Map.entry(t.getAccountId(), t.getSymbol()));
        }
        keys.forEach(k -> includeLegacy(k.getKey(), k.getValue()));
    }

    public static SideEffect inclusionEffect(OptionTradeEntity lot) {
        return new SideEffect(lot.getId(), TradeAction.STO.name(), lot.contract().label(), SideEffect.COVERAGE,
                BEFORE_INCLUSION, AFTER_INCLUSION);
    }

    // ------------------------------------------------------------------ reads

    /** Shares of {@code symbol} covered by CHECKED short calls now. */
    @Transactional(readOnly = true)
    public long coveredShares(long accountId, String symbol) {
        return snapshot(accountId, symbol).timeline().current(CoverageTimeline.checked()).covered();
    }

    // ------------------------------------------------------------------ timeline

    public Snapshot snapshot(long accountId, String symbol) {
        return snapshot(accountId, symbol, Proposal.none());
    }

    public Snapshot snapshot(long accountId, String symbol, Proposal p) {
        return snapshot(accountId, symbol, p, optionTrades.findByAccountIdAndSymbolAndOptionTypeOrderByTradedAtAscIdAsc(
                accountId, symbol, OptionType.CALL));
    }

    private Snapshot snapshot(long accountId, String symbol, Proposal p, List<OptionTradeEntity> calls) {
        // stock side
        List<StockTradeEntity> stockEntities = stockTrades.findByAccountIdAndSymbolOrderByTradedAtAscIdAsc(accountId,
                symbol);
        Map<Long, Long> stockSeq = new HashMap<>();
        Map<Long, Long> deliveredBy = new HashMap<>(); // ASG option id -> ASSIGN_SELL stock id
        List<StockRecord> stored = new ArrayList<>(stockEntities.size());
        for (StockTradeEntity e : stockEntities) {
            stockSeq.put(e.getId(), e.entrySeq());
            if (e.getOptionTradeId() != null) {
                deliveredBy.put(e.getOptionTradeId(), e.getId());
            }
            stored.add(e.toRecord());
        }
        if (p.newCallAssignment()) {
            deliveredBy.put(LotTrade.NOT_YET_ENTERED, StockRecord.NOT_YET_ENTERED);
        }
        Map<Long, Key> stockKeys = new LinkedHashMap<>();
        List<ShareMove> moves = new ArrayList<>();
        for (StockRecord r : p.stockChange().apply(stored)) {
            Key key = new Key(r.tradedAt(), stockSeq.getOrDefault(r.id(), Long.MAX_VALUE));
            stockKeys.put(r.id(), key);
            moves.add(new ShareMove(key, r.type().createsLot() ? r.shares() : -(long) r.shares()));
        }

        // option side: every call contract of the symbol, the proposed one replaced
        Map<Long, OptionTradeEntity> byId = new HashMap<>();
        Map<ContractKey, List<LotTrade>> contracts = new LinkedHashMap<>();
        for (OptionTradeEntity t : calls) {
            byId.put(t.getId(), t);
            contracts.computeIfAbsent(t.contract(), c -> new ArrayList<>()).add(new LotTrade(t.getId(),
                    t.getTradedAt(), t.getAction(), t.getContracts(), t.getMultiplier(), Money.ZERO, Money.ZERO, null));
        }
        if (p.contract() != null && p.contract().optionType() == OptionType.CALL
                && p.contract().symbol().equals(symbol)) {
            contracts.put(p.contract(), p.contractTrades());
        }
        List<CallLot> lots = new ArrayList<>();
        for (List<LotTrade> trades : contracts.values()) {
            Map<Long, Key> openedAt = new LinkedHashMap<>();
            Map<Long, Long> required = new HashMap<>();
            Map<Long, Key> releasedAt = new HashMap<>();
            for (ReplayedTrade step : ContractReplay.replay(trades).steps()) {
                LotTrade t = step.trade();
                long seq = optionSeq(byId, t.tradeId());
                if (t.action() == TradeAction.STO) {
                    openedAt.put(t.tradeId(), new Key(t.tradedAt(), seq));
                    required.put(t.tradeId(), (long) t.contracts() * t.multiplier());
                    continue;
                }
                Optional<CloseMatch> match = step.match();
                if (match.isEmpty()) {
                    continue;
                }
                Key release = new Key(t.tradedAt(), seq);
                if (t.action() == TradeAction.ASG) {
                    Long stockId = deliveredBy.get(t.tradeId());
                    if (stockId != null && stockKeys.containsKey(stockId)) {
                        release = stockKeys.get(stockId);
                    }
                }
                for (PositionLot covered : match.get().coveredLots()) {
                    releasedAt.put(covered.tradeId(), release);
                }
            }
            openedAt.forEach((id, key) -> {
                OptionTradeEntity e = byId.get(id);
                boolean checked = id == LotTrade.NOT_YET_ENTERED || p.treatedChecked().contains(id)
                        || (e != null && e.getCoverageStatus() == CoverageStatus.CHECKED);
                lots.add(new CallLot(id, key, required.get(id), releasedAt.get(id), checked));
            });
        }
        return new Snapshot(symbol, new CoverageTimeline(moves, lots), stockKeys);
    }

    private static long optionSeq(Map<Long, OptionTradeEntity> byId, long tradeId) {
        OptionTradeEntity e = byId.get(tradeId);
        return e == null ? Long.MAX_VALUE : e.entrySeq();
    }
}
