package com.optionmgr.stock.service;

import com.optionmgr.audit.domain.SideEffect;
import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.cash.domain.InsertionCheck;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.cash.service.NewLedgerEntry;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.domain.SaleMatch;
import com.optionmgr.stock.domain.StockMessages;
import com.optionmgr.stock.domain.StockRecord;
import com.optionmgr.stock.domain.StockReplay;
import com.optionmgr.stock.domain.StockTimes;
import com.optionmgr.stock.domain.StockTradeFilter;
import com.optionmgr.stock.domain.StockTradeType;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.repository.StockTradeRepository;
import com.optionmgr.stock.repository.StockTradeSpecifications;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-011: record a stock trade (buy / sell / transfer-in), read one back, list. Holdings (Q15) are checked by a
 * FIFO replay of the symbol's records, cash (Q68 / Q19) by the shared cash-ledger replay; both under the account's
 * cash lock, so checks and writes are serialized with every other cash-changing write of the account.
 */
@Service
public class StockTradeService {

    public static final String FUTURE_TIME_MESSAGE = "成交时间不能晚于当前时间";
    public static final String MISSING_TIME_MESSAGE = "成交时间不能为空";
    public static final String FUTURE_DATE_MESSAGE = "日期不能晚于今天";
    public static final String MISSING_DATE_MESSAGE = "日期不能为空";

    /** Ledger source types of stock records (they share the stock_trade id space). */
    public static final Set<CashSourceType> STOCK_SOURCE_TYPES = Set.of(CashSourceType.STOCK_BUY,
            CashSourceType.STOCK_SELL);

    static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("tradedAt"), Sort.Order.desc("id"));

    private final StockTradeRepository trades;
    private final CashLedgerService ledger;
    private final OptionTradeRepository optionTrades;
    private final Clock clock;

    public StockTradeService(StockTradeRepository trades, CashLedgerService ledger,
                             OptionTradeRepository optionTrades, Clock clock) {
        this.trades = trades;
        this.ledger = ledger;
        this.optionTrades = optionTrades;
        this.clock = clock;
    }

    /**
     * A new stock record whose request-level fields already passed validation (web layer).
     *
     * @param tradedAtLocal New York time of a BUY / SELL (ignored for TRANSFER_IN)
     * @param tradeDate     New York date of a TRANSFER_IN (ignored for BUY / SELL)
     */
    public record CreateCommand(StockTradeType type, String symbol, int shares, BigDecimal amount,
                                LocalDateTime tradedAtLocal, LocalDate tradeDate, String note) {
        public CreateCommand {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * A record with what is derived on read.
     *
     * @param ledgerEntryId its cash ledger entry (BUY / SELL), null for TRANSFER_IN
     * @param sale          FIFO match of a SELL under the current history; empty otherwise
     */
    public record StockTradeView(StockTradeEntity trade, Long ledgerEntryId, Optional<SaleMatch> sale) {

        public BigDecimal cashChange() {
            return trade.getType().cashChange(trade.getAmount());
        }
    }

    public record StockTradePage(List<StockTradeView> items, int page, int size, long totalItems, int totalPages) {
    }

    @Transactional
    public StockTradeView create(long accountId, CreateCommand cmd) {
        Instant tradedAt = resolveTime(cmd.type(), cmd.tradedAtLocal(), cmd.tradeDate(), clock);
        BigDecimal amount = Money.of(cmd.amount());
        String note = blankToNull(cmd.note());

        ledger.lockAccount(accountId);
        StockRecord pending = new StockRecord(StockRecord.NOT_YET_ENTERED, cmd.type(), tradedAt, cmd.shares(), amount,
                note);
        if (cmd.type() == StockTradeType.SELL) {
            checkHoldings(accountId, cmd.symbol(), records -> {
                List<StockRecord> all = new ArrayList<>(records);
                all.add(pending);
                return all;
            }, StockRecord.NOT_YET_ENTERED, "补录");
        }
        if (cmd.type() == StockTradeType.BUY) {
            InsertionCheck check = ledger.checkInsertion(accountId, tradedAt, cmd.type().cashChange(amount),
                    Money.ZERO);
            check.firstViolation().ifPresent(v -> {
                throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.insufficientAt(amount, v));
            });
        }

        StockTradeEntity saved = trades.save(new StockTradeEntity(accountId, cmd.type(), cmd.symbol(), cmd.shares(),
                amount, tradedAt, note, clock.instant()));
        Long ledgerEntryId = null;
        Optional<CashSourceType> source = cmd.type().ledgerSource();
        if (source.isPresent()) {
            CashLedgerEntryEntity entry = ledger.append(new NewLedgerEntry(accountId, tradedAt, source.get(),
                    saved.getId(), cmd.type().cashChange(amount), Money.ZERO,
                    cmd.type().describe(cmd.shares(), cmd.symbol()), note));
            ledgerEntryId = entry.getId();
        }
        return new StockTradeView(saved, ledgerEntryId, saleOf(accountId, saved));
    }

    @Transactional(readOnly = true)
    public StockTradeView get(long accountId, long id) {
        return views(accountId, List.of(find(accountId, id))).get(0);
    }

    /** Newest first (same instant: later entry first), 1-based page. */
    @Transactional(readOnly = true)
    public StockTradePage list(long accountId, StockTradeFilter filter, int page, int size) {
        Page<StockTradeEntity> result = trades.findAll(StockTradeSpecifications.matching(accountId, filter),
                PageRequest.of(page - 1, size, LIST_ORDER));
        return new StockTradePage(views(accountId, result.getContent()), page, size, result.getTotalElements(),
                result.getTotalPages());
    }

    StockTradeEntity find(long accountId, long id) {
        return trades.findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> ApiException.notFound("Stock trade " + id + " not found"));
    }

    /** Views of some records of one account: one ledger query, one FIFO replay per symbol that has a sale. */
    @Transactional(readOnly = true)
    public List<StockTradeView> views(long accountId, List<StockTradeEntity> page) {
        Map<Long, CashLedgerEntryEntity> entries = ledger.findBySources(accountId, STOCK_SOURCE_TYPES,
                page.stream().map(StockTradeEntity::getId).toList());
        Map<String, StockReplay.History> histories = new HashMap<>();
        for (String symbol : page.stream().filter(t -> t.getType().isSale())
                .map(StockTradeEntity::getSymbol).collect(Collectors.toSet())) {
            histories.put(symbol, StockReplay.replay(records(accountId, symbol)));
        }
        return page.stream().map(t -> new StockTradeView(t,
                Optional.ofNullable(entries.get(t.getId())).map(CashLedgerEntryEntity::getId).orElse(null),
                t.getType().isSale()
                        ? histories.get(t.getSymbol()).step(t.getId()).flatMap(StockReplay.Step::sale)
                        : Optional.empty())).toList();
    }

    private Optional<SaleMatch> saleOf(long accountId, StockTradeEntity t) {
        if (!t.getType().isSale()) {
            return Optional.empty();
        }
        return StockReplay.replay(records(accountId, t.getSymbol())).step(t.getId()).flatMap(StockReplay.Step::sale);
    }

    /** The account's records of a symbol as replay input. */
    public List<StockRecord> records(long accountId, String symbol) {
        return trades.findByAccountIdAndSymbolOrderByTradedAtAscIdAsc(accountId, symbol).stream()
                .map(StockTradeEntity::toRecord).toList();
    }

    /**
     * Q15: replays the symbol's records after {@code change}; every sale must stay within the shares held at its
     * time. The first violation is rejected: the {@code subjectId} record itself with "持股不足：…", another sale with
     * "{@code verb}后 … 将超过当时持股 …", a stored call assignment (FEAT-041) with "{@code verb}后 … 的被指派 … 请先删除该被指派".
     */
    public void checkHoldings(long accountId, String symbol, UnaryOperator<List<StockRecord>> change, long subjectId,
                       String verb) {
        checkHoldings(accountId, symbol, change, subjectId, verb, step -> StockMessages.saleExceedsHolding(symbol, step));
    }

    /** As above; the {@code subjectId} record's own violation is worded by {@code subjectMessage}. */
    public void checkHoldings(long accountId, String symbol, UnaryOperator<List<StockRecord>> change, long subjectId,
                              String verb, Function<StockReplay.Step, String> subjectMessage) {
        StockReplay.replay(change.apply(records(accountId, symbol))).firstViolation().ifPresent(step -> {
            String message;
            if (step.record().id() == subjectId) {
                message = subjectMessage.apply(step);
            } else if (step.record().type() == StockTradeType.ASSIGN_SELL) {
                OptionTradeEntity assignment = assignmentOf(step.record().id());
                message = StockMessages.laterAssignmentExceedsHolding(verb, assignment.getContracts(),
                        assignment.contract().label(), step);
            } else {
                message = StockMessages.laterSaleExceedsHolding(verb, symbol, step);
            }
            throw new ApiException(ErrorCode.SHARES_INSUFFICIENT, message);
        });
    }

    /**
     * FEAT-041 (Q42 extended): the stored call assignments (ASSIGN_SELL records) of a symbol whose delivered shares'
     * cost (FIFO sold cost) differs after {@code change}, as audit side effects "被指派 #4 交出成本 2,550.00 → 2,350.00".
     * Records removed by the change are not reported.
     */
    public List<SideEffect> assignedSaleCostEffects(long accountId, String symbol,
                                                    UnaryOperator<List<StockRecord>> change) {
        List<StockRecord> current = records(accountId, symbol);
        if (current.stream().noneMatch(r -> r.type() == StockTradeType.ASSIGN_SELL)) {
            return List.of();
        }
        StockReplay.History before = StockReplay.replay(current);
        StockReplay.History after = StockReplay.replay(change.apply(current));
        List<SideEffect> effects = new ArrayList<>();
        for (StockReplay.Step b : before.steps()) {
            if (b.record().type() != StockTradeType.ASSIGN_SELL || b.sale().isEmpty()) {
                continue;
            }
            Optional<SaleMatch> a = after.step(b.record().id()).flatMap(StockReplay.Step::sale);
            BigDecimal was = b.sale().get().soldCost();
            if (a.isPresent() && a.get().soldCost().compareTo(was) != 0) {
                OptionTradeEntity assignment = assignmentOf(b.record().id());
                effects.add(new SideEffect(assignment.getId(), "ASG", assignment.contract().label(),
                        SideEffect.SOLD_COST, Money.display(was), Money.display(a.get().soldCost())));
            }
        }
        return effects;
    }

    /** The ASG option record that generated the stock record {@code stockTradeId}. */
    private OptionTradeEntity assignmentOf(long stockTradeId) {
        Long optionTradeId = trades.findById(stockTradeId).map(StockTradeEntity::getOptionTradeId)
                .orElseThrow(() -> new IllegalStateException("stock trade " + stockTradeId + " has no assignment"));
        return optionTrades.findById(optionTradeId)
                .orElseThrow(() -> new IllegalStateException("assignment " + optionTradeId + " missing"));
    }

    /**
     * The stored time of a record: BUY / SELL = New York time (required, not later than now); TRANSFER_IN = 00:00
     * New York of its date (required, not later than today in New York, Q86).
     *
     * @throws ApiException VALIDATION_ERROR on the time field
     */
    static Instant resolveTime(StockTradeType type, LocalDateTime tradedAtLocal, LocalDate tradeDate, Clock clock) {
        Instant now = clock.instant();
        if (type == StockTradeType.TRANSFER_IN) {
            if (tradeDate == null) {
                throw fieldError("tradeDate", MISSING_DATE_MESSAGE);
            }
            if (tradeDate.isAfter(StockTimes.marketDate(now))) {
                throw fieldError("tradeDate", FUTURE_DATE_MESSAGE);
            }
            return StockTimes.transferInstant(tradeDate);
        }
        if (tradedAtLocal == null) {
            throw fieldError("tradedAtLocal", MISSING_TIME_MESSAGE);
        }
        Instant tradedAt = MarketTime.toInstant(tradedAtLocal);
        if (tradedAt.isAfter(now)) {
            throw fieldError("tradedAtLocal", FUTURE_TIME_MESSAGE);
        }
        return tradedAt;
    }

    static String blankToNull(String note) {
        return note == null || note.isBlank() ? null : note;
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed",
                List.of(new ErrorResponse.FieldError(field, message)));
    }
}
