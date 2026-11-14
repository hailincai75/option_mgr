package com.optionmgr.stock.service;

import com.optionmgr.audit.domain.AuditObjectType;
import com.optionmgr.audit.domain.AuditOperation;
import com.optionmgr.audit.domain.FieldChange;
import com.optionmgr.audit.domain.FieldChanges;
import com.optionmgr.audit.domain.SideEffect;
import com.optionmgr.audit.service.AuditService;
import com.optionmgr.cash.domain.CashMovement;
import com.optionmgr.cash.domain.CashReplay;
import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.cash.domain.ReplayStep;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.domain.StockMessages;
import com.optionmgr.stock.domain.StockRecord;
import com.optionmgr.stock.domain.StockTimes;
import com.optionmgr.stock.domain.StockTradeType;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.repository.StockTradeRepository;
import com.optionmgr.trade.service.CoverageService;
import com.optionmgr.trade.domain.Symbols;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-011: edit or delete a stock record. The change is applied to the whole history and replayed (holdings of the
 * symbol, Q15; the account's cash ledger, Q19) before anything is written; audited like FEAT-005 (no side effects:
 * stocks never freeze cash).
 */
@Service
public class StockTradeEditService {

    public static final String NO_CHANGES_MESSAGE = "没有修改";
    public static final String FROM_ASSIGNMENT_MESSAGE = "该股票记录由被指派生成，请在交易列表删除对应的被指派记录";

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final StockTradeService stocks;
    private final StockTradeRepository trades;
    private final CashLedgerService ledger;
    private final AuditService audit;
    private final CoverageService coverage;
    private final Clock clock;

    public StockTradeEditService(StockTradeService stocks, StockTradeRepository trades, CashLedgerService ledger,
                                 AuditService audit, CoverageService coverage, Clock clock) {
        this.coverage = coverage;
        this.stocks = stocks;
        this.trades = trades;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Editable fields (whole replacement: a missing note clears it). {@code symbol} / {@code type} are optional and
     * must equal the stored ones when given.
     */
    public record EditCommand(int shares, BigDecimal amount, LocalDateTime tradedAtLocal, LocalDate tradeDate,
                              String note, String symbol, StockTradeType type) {
        public EditCommand {
            Objects.requireNonNull(amount, "amount");
        }
    }

    @Transactional
    public StockTradeService.StockTradeView update(long accountId, long id, EditCommand cmd) {
        ledger.lockAccount(accountId);
        StockTradeEntity trade = stocks.find(accountId, id);
        rejectGenerated(trade);
        StockTradeType type = trade.getType();
        Instant tradedAt = StockTradeService.resolveTime(type, cmd.tradedAtLocal(), cmd.tradeDate(), clock);
        if ((cmd.type() != null && cmd.type() != type)
                || (cmd.symbol() != null && !Symbols.normalize(cmd.symbol()).equals(trade.getSymbol()))) {
            throw new ApiException(ErrorCode.STOCK_TRADE_IDENTITY_NOT_EDITABLE, StockMessages.IDENTITY_NOT_EDITABLE);
        }
        BigDecimal amount = Money.of(cmd.amount());
        String note = StockTradeService.blankToNull(cmd.note());
        if (cmd.shares() == trade.getShares() && amount.compareTo(trade.getAmount()) == 0
                && tradedAt.equals(trade.getTradedAt()) && Objects.equals(note, trade.getNote())) {
            throw new ApiException(ErrorCode.NO_CHANGES, NO_CHANGES_MESSAGE);
        }

        StockRecord edited = new StockRecord(id, type, tradedAt, cmd.shares(), amount, note);
        stocks.checkHoldings(accountId, trade.getSymbol(),
                records -> records.stream().map(r -> r.id() == id ? edited : r).toList(), id, "修改");
        // FEAT-039: E2 for an edited sale, then E3 from the earlier of the old and new time
        CoverageService.Snapshot coverageSnapshot = coverage.snapshot(accountId, trade.getSymbol(),
                CoverageService.Proposal.stock(records -> records.stream().map(r -> r.id() == id ? edited : r).toList()));
        if (type == StockTradeType.SELL) {
            coverage.checkSale(coverageSnapshot, id, cmd.shares());
        }
        coverage.checkInvariant(coverageSnapshot,
                tradedAt.isBefore(trade.getTradedAt()) ? tradedAt : trade.getTradedAt(), "修改");
        Optional<CashLedgerEntryEntity> entry = entryOf(trade);
        BigDecimal cashChange = type.cashChange(amount);
        if (entry.isPresent()) {
            long ledgerId = entry.get().getId();
            rejectNegative(ledger.replayWithChanges(accountId,
                    Map.of(ledgerId, new CashMovement(tradedAt, ledgerId, cashChange, Money.ZERO)), Set.of()), "修改");
        }

        Map<String, Object> before = snapshot(trade);
        List<SideEffect> effects = new ArrayList<>(stocks.assignedSaleCostEffects(accountId, trade.getSymbol(),
                records -> records.stream().map(r -> r.id() == id ? edited : r).toList()));
        trade.update(cmd.shares(), amount, tradedAt, note);
        trade.markAudited();
        entry.ifPresent(e -> ledger.updateEntry(accountId, e.getId(), tradedAt, e.getSourceType(), cashChange,
                Money.ZERO, type.describe(cmd.shares(), trade.getSymbol()), note));
        trades.flush();
        effects.addAll(coverage.includeLegacy(accountId, trade.getSymbol()));
        Map<String, Object> after = snapshot(trade);
        audit.record(new AuditService.NewAuditRecord(accountId, AuditOperation.UPDATE, AuditObjectType.STOCK_TRADE, id,
                summary(before), before, after, changes(type, before, after), effects));
        return stocks.views(accountId, List.of(trade)).get(0);
    }

    @Transactional
    public void delete(long accountId, long id) {
        ledger.lockAccount(accountId);
        StockTradeEntity trade = stocks.find(accountId, id);
        rejectGenerated(trade);
        stocks.checkHoldings(accountId, trade.getSymbol(),
                records -> records.stream().filter(r -> r.id() != id).toList(), -1, "删除");
        coverage.checkInvariant(coverage.snapshot(accountId, trade.getSymbol(),
                CoverageService.Proposal.stock(records -> records.stream().filter(r -> r.id() != id).toList())),
                trade.getTradedAt(), "删除");
        Optional<CashLedgerEntryEntity> entry = entryOf(trade);
        if (entry.isPresent()) {
            rejectNegative(ledger.replayWithChanges(accountId, Map.of(), Set.of(entry.get().getId())), "删除");
        }

        Map<String, Object> before = snapshot(trade);
        List<SideEffect> effects = new ArrayList<>(stocks.assignedSaleCostEffects(accountId, trade.getSymbol(),
                records -> records.stream().filter(r -> r.id() != id).toList()));
        entry.ifPresent(e -> ledger.deleteEntry(accountId, e.getId()));
        trades.delete(trade);
        effects.addAll(coverage.includeLegacy(accountId, trade.getSymbol()));
        audit.record(new AuditService.NewAuditRecord(accountId, AuditOperation.DELETE, AuditObjectType.STOCK_TRADE, id,
                summary(before), before, null, List.of(), effects));
    }

    /** FEAT-013 / FEAT-041: a record generated by an assignment is edited / deleted only through that assignment. */
    private static void rejectGenerated(StockTradeEntity t) {
        if (t.getType().isGenerated()) {
            throw new ApiException(ErrorCode.STOCK_TRADE_FROM_ASSIGNMENT, FROM_ASSIGNMENT_MESSAGE);
        }
    }

    private Optional<CashLedgerEntryEntity> entryOf(StockTradeEntity t) {
        Optional<CashSourceType> source = t.getType().ledgerSource();
        if (source.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Optional.ofNullable(ledger.findBySources(t.getAccountId(), Set.of(source.get()),
                        List.of(t.getId())).get(t.getId()))
                .orElseThrow(() -> new IllegalStateException("ledger entry missing for stock trade " + t.getId())));
    }

    private static void rejectNegative(List<ReplayStep> steps, String verb) {
        CashReplay.firstViolation(steps).ifPresent(v -> {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.afterChange(verb, v));
        });
    }

    /** Full content for the audit record. */
    private static Map<String, Object> snapshot(StockTradeEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("type", t.getType());
        m.put("symbol", t.getSymbol());
        m.put("shares", t.getShares());
        m.put("amount", Money.of(t.getAmount()));
        m.put("tradedAt", t.getTradedAt());
        m.put("tradedAtLocal", LOCAL_SECONDS.format(MarketTime.toLocal(t.getTradedAt())));
        m.put("tradeDate", t.getType() == StockTradeType.TRANSFER_IN
                ? StockTimes.marketDate(t.getTradedAt()).toString() : null);
        m.put("note", t.getNote());
        m.put("cashChange", t.getType().cashChange(t.getAmount()));
        return m;
    }

    /** "买入 IXQZ 200 股" (values before the change). */
    private static String summary(Map<String, Object> s) {
        return ((StockTradeType) s.get("type")).label() + " " + s.get("symbol") + " " + s.get("shares") + " 股";
    }

    private static List<FieldChange> changes(StockTradeType type, Map<String, Object> b, Map<String, Object> a) {
        FieldChanges c = new FieldChanges()
                .compare("shares", "股数", String.valueOf(b.get("shares")), String.valueOf(a.get("shares")))
                .compare("amount", type == StockTradeType.TRANSFER_IN ? "总成本" : "金额",
                        Money.display((BigDecimal) b.get("amount")), Money.display((BigDecimal) a.get("amount")));
        if (type == StockTradeType.TRANSFER_IN) {
            c.compare("tradeDate", "日期", (String) b.get("tradeDate"), (String) a.get("tradeDate"));
        } else {
            c.compare("tradedAt", "时间", MarketTime.displayMinute((Instant) b.get("tradedAt")),
                    MarketTime.displayMinute((Instant) a.get("tradedAt")));
        }
        return c.compare("note", "备注", (String) b.get("note"), (String) a.get("note")).list();
    }
}
