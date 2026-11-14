package com.optionmgr.trade.service;

import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.cash.domain.InsertionCheck;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.cash.service.NewLedgerEntry;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.domain.StockMessages;
import com.optionmgr.stock.domain.StockRecord;
import com.optionmgr.stock.domain.StockTradeType;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.repository.StockTradeRepository;
import com.optionmgr.stock.service.StockTradeService;
import com.optionmgr.trade.domain.AssignmentTerms;
import com.optionmgr.trade.domain.CallAssignmentTerms;
import com.optionmgr.trade.domain.CloseMatch;
import com.optionmgr.trade.domain.ContractHistory;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.LotTrade;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.ReplayedTrade;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.domain.TradeRejectionMessages;
import com.optionmgr.trade.domain.ViolationKind;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-014: a long call / put is exercised. The exercise is an option_trade row with action EXR (same id space = entry
 * order as trades) at 16:00 New York of the exercise date (Q81), net amount 0, covering the earliest whole long lots
 * (Q112 / Q21) with option realized P&amp;L 0 (Q111): the premium goes into the stock leg.
 * <ul>
 *   <li>Call: buys the shares at the strike (rounded up, Q96): ledger entry OPTION_EXERCISE -(purchase + fee) and one
 *       generated EXERCISE_BUY lot whose cost = purchase + premium + fee ({@link AssignmentTerms} with the
 *       cash-signed, negative premium of long lots).</li>
 *   <li>Put: delivers shares held at the strike (rounded down, Q99): ledger entry +(strike amount - fee) and one
 *       generated EXERCISE_SELL whose proceeds = strike amount - premium - fee, sold FIFO like a SELL
 *       ({@link CallAssignmentTerms}). Shares held (Q15), uncovered shares (Q113) and the covered-call invariant
 *       (FEAT-039 E3) are checked.</li>
 * </ul>
 * Deleting goes through {@link OptionTradeEditService}.
 */
@Service
public class OptionExerciseService {

    private final OptionTradeRepository trades;
    private final OptionTradeService tradeService;
    private final StockTradeRepository stockTrades;
    private final CashLedgerService ledger;
    private final StockTradeService stocks;
    private final CoverageService coverage;
    private final Clock clock;

    public OptionExerciseService(OptionTradeRepository trades, OptionTradeService tradeService,
                                 StockTradeRepository stockTrades, CashLedgerService ledger,
                                 StockTradeService stocks, CoverageService coverage, Clock clock) {
        this.trades = trades;
        this.tradeService = tradeService;
        this.stockTrades = stockTrades;
        this.ledger = ledger;
        this.stocks = stocks;
        this.coverage = coverage;
        this.clock = clock;
    }

    /**
     * @param fee  exercise fee, &gt;= 0 (null = 0.00)
     * @param note optional; blank is stored as null
     */
    public record ExerciseCommand(ContractKey contract, int contracts, LocalDate exerciseDate, BigDecimal fee,
                                  String note) {
        public ExerciseCommand {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(exerciseDate, "exerciseDate");
        }
    }

    /** Checks in the order of the API contract; any failure throws {@link ApiException} and nothing is saved. */
    @Transactional
    public OptionTradeService.TradeView exercise(long accountId, ExerciseCommand cmd) {
        ContractKey contract = cmd.contract();
        BigDecimal fee = cmd.fee() == null ? Money.ZERO : Money.of(cmd.fee());
        String note = cmd.note() == null || cmd.note().isBlank() ? null : cmd.note();

        Instant at = AssignmentTerms.recordedAt(cmd.exerciseDate());
        ledger.lockAccount(accountId);
        OptionTradeService.ContractTrades existing = tradeService.load(accountId, contract);
        LotTrade candidate = new LotTrade(LotTrade.NOT_YET_ENTERED, at, TradeAction.EXR, cmd.contracts(),
                LotTrade.INHERIT_MULTIPLIER, Money.ZERO, Money.ZERO, note);
        ContractHistory history = OptionTradeService.replayWith(existing, candidate);
        ReplayedTrade step = history.step(LotTrade.NOT_YET_ENTERED);

        // 2. long positions only: a short position is assigned, not exercised
        if (step.violations().contains(ViolationKind.CLOSE_WRONG_DIRECTION)) {
            throw new ApiException(ErrorCode.EXERCISE_SHORT_POSITION, TradeRejectionMessages.EXERCISE_SHORT_POSITION);
        }

        // 3. Q81 (as Q76 / Q94): not later than the expiration date, today, now
        String dateProblem = AssignmentTerms.dateProblem("行权日期", cmd.exerciseDate(), contract.expirationDate(),
                clock.instant());
        if (dateProblem != null) {
            throw new ApiException(ErrorCode.EXERCISE_DATE_INVALID, dateProblem);
        }

        // 4. a long position at the exercise time
        if (step.violations().contains(ViolationKind.NO_POSITION)) {
            throw new ApiException(ErrorCode.NO_POSITION_TO_EXERCISE,
                    TradeRejectionMessages.noPositionToExercise(contract, at) + expiredHint(history));
        }
        // 5. whole lots (Q112 / Q21), then 6. every later record of the contract
        OptionTradeService.rejectFirstViolation(contract, history, k -> true);
        CloseMatch match = step.match().orElseThrow();
        int multiplier = step.effectiveMultiplier();
        if (contract.optionType() == OptionType.PUT) {
            return exercisePut(accountId, cmd, existing, history, match, multiplier, fee, note, at);
        }

        // call: later assignments / exercises re-matched to other lots keep a positive stock leg
        AssignmentTerms terms = AssignmentTerms.of(contract, cmd.contracts(), multiplier, fee, match);
        List<OptionTradeService.CostCorrection> costs = tradeService.costCorrections(accountId, contract, existing,
                history, LotTrade.NOT_YET_ENTERED);
        OptionTradeService.rejectNonPositiveCost(costs, "补录");

        // 11. cash (Q68, Q19): the purchase and the fee come out of available cash
        Map<Long, BigDecimal> corrections = OptionTradeService.frozenCorrections(existing, history);
        InsertionCheck check = ledger.checkInsertion(accountId, at, terms.cashChange(), terms.frozenChange(),
                corrections);
        check.firstViolation().ifPresent(v -> {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT,
                    CashShortfallMessages.insufficientAt(terms.availableDecrease(), v));
        });

        Instant now = clock.instant();
        OptionTradeEntity exercise = trades.save(OptionTradeEntity.exercise(accountId, contract, cmd.contracts(),
                multiplier, fee, at, note, now));
        ledger.append(new NewLedgerEntry(accountId, at, CashSourceType.OPTION_EXERCISE, exercise.getId(),
                terms.cashChange(), terms.frozenChange(),
                AssignmentTerms.describe(TradeAction.EXR, contract, cmd.contracts(), multiplier), note));
        stockTrades.save(StockTradeEntity.exercised(accountId, StockTradeType.EXERCISE_BUY, contract.symbol(),
                terms.shares(), terms.stockCost(), at, exercise.getId(), now));
        ledger.correctFrozenChanges(accountId, corrections);
        OptionTradeService.applyCostCorrections(costs);
        stockTrades.flush();
        coverage.includeLegacy(accountId, contract.symbol()); // FEAT-039: more shares may cover legacy lots
        return tradeService.views(accountId, List.of(exercise)).get(0);
    }

    /**
     * Steps 7-11 of a put exercise: proceeds &gt; 0, shares held (Q15), uncovered shares (Q113), the covered-call
     * invariant (FEAT-039 E3), cash. Saves the EXR record, its OPTION_EXERCISE entry and the EXERCISE_SELL record.
     */
    private OptionTradeService.TradeView exercisePut(long accountId, ExerciseCommand cmd,
                                                     OptionTradeService.ContractTrades existing,
                                                     ContractHistory history, CloseMatch match, int multiplier,
                                                     BigDecimal fee, String note, Instant at) {
        ContractKey contract = cmd.contract();
        CallAssignmentTerms terms = CallAssignmentTerms.of(contract, cmd.contracts(), multiplier, fee, match);

        // 7. sale proceeds > 0 (this exercise, and later ones re-matched to other lots)
        if (terms.proceeds().signum() <= 0) {
            throw new ApiException(ErrorCode.EXERCISE_PROCEEDS_NOT_POSITIVE, "行权后股票卖出实收将为 "
                    + Money.display(terms.proceeds()) + "，请检查行权费和被行权批次的开仓净额");
        }
        List<OptionTradeService.CostCorrection> costs = tradeService.costCorrections(accountId, contract, existing,
                history, LotTrade.NOT_YET_ENTERED);
        OptionTradeService.rejectNonPositiveCost(costs, "补录");

        // 8. shares (Q15): the delivered shares are sold at (exercise time, new stock record id)
        StockRecord pending = new StockRecord(StockRecord.NOT_YET_ENTERED, StockTradeType.EXERCISE_SELL, at,
                terms.shares(), terms.proceeds(), null);
        UnaryOperator<List<StockRecord>> withPending = records -> {
            List<StockRecord> all = new ArrayList<>(records);
            all.add(pending);
            return all;
        };
        stocks.checkHoldings(accountId, contract.symbol(), withPending, StockRecord.NOT_YET_ENTERED, "补录",
                s -> StockMessages.exerciseExceedsHolding(contract.symbol(), s));

        // 9. Q113: covered shares cannot be delivered; 10. FEAT-039 E3 from the exercise time on
        CoverageService.Snapshot snapshot = coverage.snapshot(accountId, contract.symbol(),
                CoverageService.Proposal.stock(withPending));
        coverage.checkSale(snapshot, StockRecord.NOT_YET_ENTERED, terms.shares(), true);
        coverage.checkInvariant(snapshot, at, "行权");

        // 11. cash (Q19): the exercise only adds cash (proceeds > 0 implies strike amount > fee)
        Map<Long, BigDecimal> corrections = OptionTradeService.frozenCorrections(existing, history);
        InsertionCheck check = ledger.checkInsertion(accountId, at, terms.cashChange(), terms.frozenChange(),
                corrections);
        check.firstViolation().ifPresent(v -> {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT,
                    CashShortfallMessages.insufficientAt(terms.availableDecrease(), v));
        });

        Instant now = clock.instant();
        OptionTradeEntity exercise = trades.save(OptionTradeEntity.exercise(accountId, contract, cmd.contracts(),
                multiplier, fee, at, note, now));
        ledger.append(new NewLedgerEntry(accountId, at, CashSourceType.OPTION_EXERCISE, exercise.getId(),
                terms.cashChange(), terms.frozenChange(),
                CallAssignmentTerms.describe(TradeAction.EXR, contract, cmd.contracts(), multiplier), note));
        stockTrades.save(StockTradeEntity.exercised(accountId, StockTradeType.EXERCISE_SELL, contract.symbol(),
                terms.shares(), terms.proceeds(), at, exercise.getId(), now));
        ledger.correctFrozenChanges(accountId, corrections);
        OptionTradeService.applyCostCorrections(costs);
        stockTrades.flush();
        coverage.includeLegacy(accountId, contract.symbol());
        return tradeService.views(accountId, List.of(exercise)).get(0);
    }

    /**
     * "（已于 2026-11-20 16:00 到期作废；如果实际是行权，请先删除该到期作废）" when the position was closed by an expiry (the last
     * valid record before the new exercise is an EXP); otherwise "".
     */
    private static String expiredHint(ContractHistory history) {
        ReplayedTrade last = null;
        for (ReplayedTrade s : history.steps()) {
            if (s.trade().tradeId() == LotTrade.NOT_YET_ENTERED) {
                break;
            }
            if (s.isValid()) {
                last = s;
            }
        }
        if (last == null || last.trade().action() != TradeAction.EXP) {
            return "";
        }
        return "（已于 " + MarketTime.displayMinute(last.trade().tradedAt()) + " 到期作废；如果实际是行权，请先删除该到期作废）";
    }
}
