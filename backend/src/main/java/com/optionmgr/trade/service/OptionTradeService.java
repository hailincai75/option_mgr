package com.optionmgr.trade.service;

import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.cash.domain.InsertionCheck;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.cash.service.NewLedgerEntry;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionEvent;
import com.optionmgr.trade.domain.PositionInsertionCheck;
import com.optionmgr.trade.domain.PositionReplay;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.domain.TradeRejectionMessages;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import com.optionmgr.trade.repository.OptionTradeSpecifications;
import com.optionmgr.trade.domain.TradeFilter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-001: record an opening option trade (BTO / STO) and its cash effect; read a trade back. */
@Service
public class OptionTradeService {

    public static final String FUTURE_TIME_MESSAGE = "成交时间不能晚于当前时间";

    /**
     * Ledger source types written for option trades; all reference option_trade.id. FEAT-004 adds its close
     * type (e.g. OPTION_CLOSE) here so closes show up in the list and by id without other changes.
     */
    static final Set<CashSourceType> TRADE_SOURCE_TYPES = Set.of(CashSourceType.OPTION_OPEN);

    /** Trade list order: newest first, same instant: later entered (higher id) first. */
    static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("tradedAt"), Sort.Order.desc("id"));

    private final OptionTradeRepository trades;
    private final CashLedgerService ledger;
    private final Clock clock;

    public OptionTradeService(OptionTradeRepository trades, CashLedgerService ledger, Clock clock) {
        this.trades = trades;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * An opening trade whose fields already passed request validation (web layer).
     *
     * @param tradedAtLocal New York wall-clock trade time
     * @param note          optional; blank is stored as null
     */
    public record OpenCommand(ContractKey contract, TradeAction action, int contracts, int multiplier,
                              BigDecimal netAmount, LocalDateTime tradedAtLocal, String note) {
        public OpenCommand {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(netAmount, "netAmount");
            Objects.requireNonNull(tradedAtLocal, "tradedAtLocal");
        }
    }

    /** One page of trades, see {@link #list}. {@code page} is 1-based. */
    public record TradePage(List<TradeView> items, int page, int size, long totalItems, int totalPages) {
    }

    /** A stored trade with the ledger entry it produced (its cash and frozen-cash effect). */
    public record TradeView(OptionTradeEntity trade, CashLedgerEntryEntity ledgerEntry) {
    }

    /**
     * Checks in the order of the requirement (fields, Q7, Q16, Q23, cash) and saves trade + ledger entry in one
     * transaction; any failure throws {@link ApiException} and nothing is saved.
     */
    @Transactional
    public TradeView open(OpenCommand cmd) {
        // 1. field rules that need the clock / the trade's New York date
        Instant tradedAt = MarketTime.toInstant(cmd.tradedAtLocal());
        checkTimes(cmd.contract().expirationDate(), tradedAt);
        OpenTradeTerms terms = new OpenTradeTerms(cmd.contract(), cmd.action(), cmd.contracts(), cmd.multiplier(),
                cmd.netAmount());

        // Every cash-changing transaction holds the account lock: the position and cash checks below see a
        // stable state until commit.
        ledger.lockAccount();

        // 2. Q7 long/short conflict and 4. Q23 multiplier, both by replaying the contract's trades
        ContractKey contract = terms.contract();
        List<PositionEvent> existing = trades
                .findBySymbolAndExpirationDateAndStrikeAndOptionTypeOrderByTradedAtAscIdAsc(
                        contract.symbol(), contract.expirationDate(), contract.strike(), contract.optionType())
                .stream().map(OptionTradeEntity::toPositionEvent).toList();
        PositionInsertionCheck positions = PositionReplay.checkInsertion(existing,
                PositionEvent.pending(tradedAt, terms.action(), terms.contracts(), terms.multiplier()));
        positions.directionConflict().ifPresent(step -> {
            throw new ApiException(ErrorCode.POSITION_DIRECTION_CONFLICT,
                    TradeRejectionMessages.directionConflict(contract, positions, step));
        });

        // 3. Q16
        if (terms.action() == TradeAction.STO && contract.optionType() == OptionType.CALL) {
            throw new ApiException(ErrorCode.SELL_CALL_NOT_SUPPORTED, TradeRejectionMessages.SELL_CALL_NOT_SUPPORTED);
        }

        positions.multiplierMismatch().ifPresent(step -> {
            throw new ApiException(ErrorCode.MULTIPLIER_MISMATCH,
                    TradeRejectionMessages.multiplierMismatch(contract, positions, step));
        });

        // 5. cash (Q13, Q18, Q19)
        checkCash(terms, tradedAt);

        OptionTradeEntity trade = trades.save(new OptionTradeEntity(contract, terms.action(), terms.contracts(),
                terms.multiplier(), terms.netAmount(), tradedAt, blankToNull(cmd.note()), clock.instant()));
        CashLedgerEntryEntity entry = ledger.append(new NewLedgerEntry(tradedAt, CashSourceType.OPTION_OPEN,
                trade.getId(), terms.cashChange(), terms.frozenChange(), terms.description(), trade.getNote()));
        return new TradeView(trade, entry);
    }

    @Transactional(readOnly = true)
    public TradeView get(long id) {
        OptionTradeEntity trade = trades.findById(id)
                .orElseThrow(() -> ApiException.notFound("Option trade " + id + " not found"));
        return withLedgerEntries(List.of(trade)).get(0);
    }

    /** One page of the trade list (FEAT-002): newest first, same instant: later entered first. */
    @Transactional(readOnly = true)
    public TradePage list(TradeFilter filter, int page, int size) {
        Page<OptionTradeEntity> result = trades.findAll(OptionTradeSpecifications.matching(filter),
                PageRequest.of(page - 1, size, LIST_ORDER));
        return new TradePage(withLedgerEntries(result.getContent()), page, size, result.getTotalElements(),
                result.getTotalPages());
    }

    private List<TradeView> withLedgerEntries(List<OptionTradeEntity> page) {
        Map<Long, CashLedgerEntryEntity> entries = ledger.findBySources(TRADE_SOURCE_TYPES,
                page.stream().map(OptionTradeEntity::getId).toList());
        return page.stream().map(t -> {
            CashLedgerEntryEntity e = entries.get(t.getId());
            if (e == null) {
                throw new IllegalStateException("ledger entry missing for option trade " + t.getId());
            }
            return new TradeView(t, e);
        }).toList();
    }

    private void checkTimes(LocalDate expirationDate, Instant tradedAt) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        LocalDate tradeDate = MarketTime.toLocal(tradedAt).toLocalDate();
        if (expirationDate.isBefore(tradeDate)) {
            errors.add(new ErrorResponse.FieldError("expirationDate", "到期日不能早于成交日（" + tradeDate + "）"));
        }
        if (tradedAt.isAfter(clock.instant())) {
            errors.add(new ErrorResponse.FieldError("tradedAtLocal", FUTURE_TIME_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request validation failed", errors);
        }
    }

    private void checkCash(OpenTradeTerms terms, Instant tradedAt) {
        InsertionCheck check = ledger.checkInsertion(tradedAt, terms.cashChange(), terms.frozenChange());
        if (terms.freezesCollateral()) {
            // Q18: available cash before this trade (premium not counted) must cover the collateral.
            BigDecimal collateral = terms.collateral();
            BigDecimal availableBefore = check.inserted().before().available();
            if (availableBefore.compareTo(collateral) < 0) {
                throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.notEnoughNow(
                        CashShortfallMessages.INSUFFICIENT_FOR_PUT_COLLATERAL, collateral, availableBefore));
            }
            if (!check.allowed()) {
                throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.insufficient(
                        CashShortfallMessages.INSUFFICIENT_FOR_PUT_COLLATERAL, collateral, check));
            }
            return;
        }
        if (!check.allowed()) {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT,
                    CashShortfallMessages.insufficient(terms.netAmount(), check));
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
