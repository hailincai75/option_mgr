package com.optionmgr.trade.service;

import com.optionmgr.cash.domain.CashShortfallMessages;
import com.optionmgr.cash.domain.CashSourceType;
import com.optionmgr.cash.domain.InsertionCheck;
import com.optionmgr.cash.repository.CashLedgerEntryEntity;
import com.optionmgr.cash.service.CashLedgerService;
import com.optionmgr.cash.service.NewLedgerEntry;
import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.repository.StockTradeRepository;
import com.optionmgr.trade.domain.AssignmentTerms;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-013: a short put is assigned. The assignment is an option_trade row with action ASG (same id space = entry
 * order as trades) at 16:00 New York of the assignment date (Q76), net amount 0, covering the earliest whole short
 * lots (Q73 / Q21) with option realized P&amp;L 0 (Q8). The frozen cash of those lots buys the shares at the strike
 * (rounded up, Q96): one ledger entry OPTION_ASSIGN (cash -(purchase + fee), frozen -released) and one generated
 * stock lot ASSIGN_BUY whose cost = purchase - premium + fee. Deleting goes through {@link OptionTradeEditService}.
 */
@Service
public class OptionAssignmentService {

    private final OptionTradeRepository trades;
    private final OptionTradeService tradeService;
    private final StockTradeRepository stockTrades;
    private final CashLedgerService ledger;
    private final Clock clock;

    public OptionAssignmentService(OptionTradeRepository trades, OptionTradeService tradeService,
                                   StockTradeRepository stockTrades, CashLedgerService ledger, Clock clock) {
        this.trades = trades;
        this.tradeService = tradeService;
        this.stockTrades = stockTrades;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @param fee  assignment fee, &gt;= 0 (null = 0.00)
     * @param note optional; blank is stored as null
     */
    public record AssignCommand(ContractKey contract, int contracts, LocalDate assignmentDate, BigDecimal fee,
                                String note) {
        public AssignCommand {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(assignmentDate, "assignmentDate");
        }
    }

    /** Checks in the order of the API contract; any failure throws {@link ApiException} and nothing is saved. */
    @Transactional
    public OptionTradeService.TradeView assign(long accountId, AssignCommand cmd) {
        ContractKey contract = cmd.contract();
        BigDecimal fee = cmd.fee() == null ? Money.ZERO : Money.of(cmd.fee());
        String note = cmd.note() == null || cmd.note().isBlank() ? null : cmd.note();

        Instant at = AssignmentTerms.recordedAt(cmd.assignmentDate());
        ledger.lockAccount(accountId);
        OptionTradeService.ContractTrades existing = tradeService.load(accountId, contract);
        LotTrade candidate = new LotTrade(LotTrade.NOT_YET_ENTERED, at, TradeAction.ASG, cmd.contracts(),
                LotTrade.INHERIT_MULTIPLIER, Money.ZERO, Money.ZERO, note);
        ContractHistory history = OptionTradeService.replayWith(existing, candidate);
        ReplayedTrade step = history.step(LotTrade.NOT_YET_ENTERED);

        // 2. short puts only: a long position (call or put) is exercised, not assigned (FEAT-014);
        //    short calls follow in FEAT-041
        if (step.violations().contains(ViolationKind.CLOSE_WRONG_DIRECTION)) {
            throw new ApiException(ErrorCode.ASSIGN_LONG_POSITION, "多头持仓不能被指派（主动行权见 FEAT-014）");
        }
        if (contract.optionType() == OptionType.CALL) {
            throw new ApiException(ErrorCode.ASSIGN_CALL_NOT_SUPPORTED, "卖出 call 的被指派暂不支持（FEAT-041）");
        }

        // 3. Q76 / Q94: not later than the expiration date, today, now
        String dateProblem = AssignmentTerms.dateProblem(cmd.assignmentDate(), contract.expirationDate(),
                clock.instant());
        if (dateProblem != null) {
            throw new ApiException(ErrorCode.ASSIGNMENT_DATE_INVALID, dateProblem);
        }

        // 4. a short position at the assignment time
        if (step.violations().contains(ViolationKind.NO_POSITION)) {
            throw new ApiException(ErrorCode.NO_POSITION_TO_ASSIGN,
                    TradeRejectionMessages.noPositionToAssign(contract, at) + expiredHint(history));
        }
        // 5. whole lots (Q73 / Q21), then 6. every later record of the contract
        OptionTradeService.rejectFirstViolation(contract, history, k -> true);
        CloseMatch match = step.match().orElseThrow();
        int multiplier = step.effectiveMultiplier();
        AssignmentTerms terms = AssignmentTerms.of(contract, cmd.contracts(), multiplier, fee, match);

        // 7. stock cost > 0 (this assignment, and later ones re-matched to other lots)
        if (terms.stockCost().signum() <= 0) {
            throw new ApiException(ErrorCode.STOCK_COST_NOT_POSITIVE, "被指派后股票成本将为 "
                    + Money.display(terms.stockCost()) + "，请检查被指派批次的开仓净额");
        }
        List<OptionTradeService.CostCorrection> costs = tradeService.costCorrections(accountId, contract, existing,
                history, LotTrade.NOT_YET_ENTERED);
        OptionTradeService.rejectNonPositiveCost(costs, "补录");

        // 8. cash (Q19): the frozen cash of the lots pays the shares; fee / rounding may need available cash
        Map<Long, BigDecimal> corrections = OptionTradeService.frozenCorrections(existing, history);
        InsertionCheck check = ledger.checkInsertion(accountId, at, terms.cashChange(), terms.frozenChange(),
                corrections);
        check.firstViolation().ifPresent(v -> {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT,
                    CashShortfallMessages.insufficientAt(terms.availableDecrease(), v));
        });

        Instant now = clock.instant();
        OptionTradeEntity assignment = trades.save(OptionTradeEntity.assignment(accountId, contract, cmd.contracts(),
                multiplier, fee, at, note, now));
        CashLedgerEntryEntity entry = ledger.append(new NewLedgerEntry(accountId, at, CashSourceType.OPTION_ASSIGN,
                assignment.getId(), terms.cashChange(), terms.frozenChange(),
                AssignmentTerms.describe(contract, cmd.contracts(), multiplier), note));
        StockTradeEntity lot = stockTrades.save(StockTradeEntity.assignedLot(accountId, contract.symbol(),
                terms.shares(), terms.stockCost(), at, assignment.getId(), now));
        ledger.correctFrozenChanges(accountId, corrections);
        OptionTradeService.applyCostCorrections(costs);
        return new OptionTradeService.TradeView(assignment, entry, Optional.of(match), lot);
    }

    /**
     * "（已于 2026-11-20 16:00 到期作废；如果实际是被指派，请先删除该到期作废）" when the position was closed by an expiry
     * (the last valid record before the new assignment is an EXP); otherwise "".
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
        return "（已于 " + MarketTime.displayMinute(last.trade().tradedAt()) + " 到期作废；如果实际是被指派，请先删除该到期作废）";
    }
}
