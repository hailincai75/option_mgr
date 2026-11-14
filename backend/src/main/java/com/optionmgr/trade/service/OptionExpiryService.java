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
import com.optionmgr.trade.domain.CloseMatch;
import com.optionmgr.trade.domain.ContractHistory;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.ExpirationTimes;
import com.optionmgr.trade.domain.ExpiryReplay;
import com.optionmgr.trade.domain.LotTrade;
import com.optionmgr.trade.domain.OpenTradeTerms;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionLot;
import com.optionmgr.trade.domain.ReplayedTrade;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.domain.TradeRejectionMessages;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-012: expire a position worthless. The expiry is an option_trade row with action EXP (same id space = entry
 * order as trades), recorded at the expiration cutoff (16:00 New York, Q63), net amount 0, covering every lot open at
 * the cutoff (Q65). Its realized P&amp;L and covered lots are derived on read like a close's; the frozen cash it
 * releases (short put) is a ledger entry OPTION_EXPIRE with amount 0, written only when something is released.
 * Deleting it goes through {@link OptionTradeEditService#delete} (audited); it cannot be edited (Q66).
 */
@Service
public class OptionExpiryService {

    private static final DateTimeFormatter DATE_HH_MM_SS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OptionTradeRepository trades;
    private final OptionTradeService tradeService;
    private final CashLedgerService ledger;
    private final CoverageService coverage;
    private final Clock clock;

    public OptionExpiryService(OptionTradeRepository trades, OptionTradeService tradeService, CashLedgerService ledger,
                               CoverageService coverage, Clock clock) {
        this.coverage = coverage;
        this.trades = trades;
        this.tradeService = tradeService;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @param contracts the expiry size the user saw on the positions page (only used to detect a changed position)
     */
    public record ExpireCommand(ContractKey contract, int contracts) {
        public ExpireCommand {
            Objects.requireNonNull(contract, "contract");
        }
    }

    /** One item of a batch that failed a FEAT-012 check: its index in the request and the single-expire error. */
    public record BatchFailure(int index, ContractKey contract, ErrorCode code, String message) {
    }

    /** FEAT-015: at least one item of a batch failed; nothing was saved. Failures are in processing order. */
    public static class BatchExpirationRejectedException extends RuntimeException {
        private final List<BatchFailure> failures;

        public BatchExpirationRejectedException(List<BatchFailure> failures) {
            super("有 " + failures.size() + " 个持仓不能作废，整批未保存");
            this.failures = List.copyOf(failures);
        }

        public List<BatchFailure> failures() {
            return failures;
        }
    }

    /**
     * FEAT-015 result: the expiries in processing order and their totals (scale 2).
     *
     * @param realizedPnlTotal  sum of the expiries' realized P&amp;L
     * @param frozenChangeTotal sum of their frozen changes (released frozen cash, so &le; 0)
     */
    public record BatchResult(List<OptionTradeService.TradeView> expirations, BigDecimal realizedPnlTotal,
                              BigDecimal frozenChangeTotal) {
    }

    /**
     * Q116 / Q35 processing order: expiration date, symbol, strike (by value), call before put. The request order
     * does not matter.
     */
    public static final Comparator<ContractKey> PROCESSING_ORDER = Comparator.comparing(ContractKey::expirationDate)
            .thenComparing(ContractKey::symbol)
            .thenComparing(ContractKey::strike)
            .thenComparing(k -> k.optionType() == OptionType.CALL ? 0 : 1);

    /**
     * Checks in the order of the API contract (time, position at the cutoff, same contracts, no late trades, cash)
     * and saves the expiry (+ ledger entry if it releases frozen cash); any failure throws {@link ApiException} and
     * nothing is saved.
     */
    @Transactional
    public OptionTradeService.TradeView expire(long accountId, ExpireCommand cmd) {
        ledger.lockAccount(accountId);
        return save(accountId, cmd.contract(), check(accountId, cmd));
    }

    /**
     * FEAT-015: expire several positions (distinct contracts, validated by the caller) all-or-nothing (Q116). Every
     * item gets FEAT-012 checks 2-5 in processing order; all failures are collected and thrown together as
     * {@link BatchExpirationRejectedException}. Otherwise each item is saved exactly like a single expiry, in
     * processing order (so same-cutoff expiries replay in that order by id), in one transaction.
     */
    @Transactional
    public BatchResult expireAll(long accountId, List<ExpireCommand> cmds) {
        ledger.lockAccount(accountId);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cmds.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(i -> cmds.get(i).contract(), PROCESSING_ORDER));

        List<BatchFailure> failures = new ArrayList<>();
        List<Checked> checked = new ArrayList<>();
        for (int i : order) {
            ExpireCommand cmd = cmds.get(i);
            try {
                checked.add(check(accountId, cmd));
            } catch (ApiException e) {
                failures.add(new BatchFailure(i, cmd.contract(), e.code(), e.getMessage()));
            }
        }
        if (!failures.isEmpty()) {
            throw new BatchExpirationRejectedException(failures);
        }

        List<OptionTradeService.TradeView> saved = new ArrayList<>();
        BigDecimal pnl = Money.ZERO;
        BigDecimal frozen = Money.ZERO;
        for (int n = 0; n < order.size(); n++) {
            OptionTradeService.TradeView view = save(accountId, cmds.get(order.get(n)).contract(), checked.get(n));
            saved.add(view);
            pnl = pnl.add(view.closeMatch().orElseThrow().realizedPnl());
            frozen = frozen.add(view.frozenChange());
        }
        return new BatchResult(saved, Money.of(pnl), Money.of(frozen));
    }

    /** The contract's trades and its size at the cutoff, after checks 2-5 passed. */
    private record Checked(OptionTradeService.ContractTrades existing, int held) {
    }

    /** FEAT-012 checks 2-5 for one contract (the account must be locked); throws {@link ApiException}. */
    private Checked check(long accountId, ExpireCommand cmd) {
        ContractKey contract = cmd.contract();

        // 2. Q10: from 17:30 New York on the expiration date
        if (!ExpirationTimes.expirable(contract.expirationDate(), clock.instant())) {
            throw new ApiException(ErrorCode.EXPIRY_TOO_EARLY,
                    "到期日（" + contract.expirationDate() + "）美东 17:30 起才能作废");
        }

        OptionTradeService.ContractTrades existing = tradeService.load(accountId, contract);

        // 3. a position at the cutoff (records at the same instant are earlier entries, so they come first)
        List<PositionLot> lots = ExpiryReplay.lotsAtCutoff(contract, existing.trades());
        if (lots.isEmpty()) {
            throw new ApiException(ErrorCode.NO_POSITION_TO_EXPIRE, TradeRejectionMessages.noPositionToExpire(contract));
        }
        // 4. the position the user saw (the expiry size: the position at the cutoff)
        int held = lots.stream().mapToInt(PositionLot::remainingContracts).sum();
        if (held != cmd.contracts()) {
            throw new ApiException(ErrorCode.POSITION_CHANGED, "持仓已变化（当前 " + held + " 张），请刷新后重试");
        }
        // 5. Q83: any trade later than the cutoff (stored before FEAT-012) conflicts with the expiry
        Optional<LotTrade> late = ExpiryReplay.firstLateTrade(contract, existing.trades());
        if (late.isPresent()) {
            LotTrade t = late.get();
            throw new ApiException(ErrorCode.EXPIRY_BLOCKED_BY_LATE_TRADE, lateTime(t.tradedAt()) + " 的 "
                    + t.action().displayName() + " " + t.contracts() + " 张晚于到期日美东 16:00，与到期作废冲突；"
                    + "请先把这笔交易的时间改为 16:00 或之前，或删除它");
        }
        return new Checked(existing, held);
    }

    /** Check 6 (cash) and save the expiry of a checked contract. */
    private OptionTradeService.TradeView save(long accountId, ContractKey contract, Checked checked) {
        Instant cutoff = ExpirationTimes.tradingCutoff(contract.expirationDate());
        OptionTradeService.ContractTrades existing = checked.existing();
        int held = checked.held();

        // the expiry covers every lot at the cutoff; nothing after it, so the whole history stays valid
        LotTrade candidate = new LotTrade(LotTrade.NOT_YET_ENTERED, cutoff, TradeAction.EXP, held,
                LotTrade.INHERIT_MULTIPLIER, Money.ZERO, Money.ZERO, null);
        ContractHistory history = OptionTradeService.replayWith(existing, candidate);
        OptionTradeService.rejectFirstViolation(contract, history, k -> true);
        ReplayedTrade step = history.step(LotTrade.NOT_YET_ENTERED);
        CloseMatch match = step.match().orElseThrow();
        BigDecimal released = match.releasedFrozen();

        // 6. cash (Q19): only frozen cash is released, so this cannot fail; checked anyway
        Map<Long, BigDecimal> corrections = OptionTradeService.frozenCorrections(existing, history);
        InsertionCheck check = ledger.checkInsertion(accountId, cutoff, Money.ZERO, released.negate(), corrections);
        if (!check.allowed()) {
            throw new ApiException(ErrorCode.CASH_INSUFFICIENT, CashShortfallMessages.insufficient(Money.ZERO, check));
        }

        int multiplier = step.effectiveMultiplier();
        OptionTradeEntity expiry = trades.save(new OptionTradeEntity(accountId, contract, TradeAction.EXP, held,
                multiplier, Money.ZERO, cutoff, null, clock.instant()));
        CashLedgerEntryEntity entry = null;
        if (released.signum() > 0) {
            entry = ledger.append(new NewLedgerEntry(accountId, cutoff, CashSourceType.OPTION_EXPIRE, expiry.getId(),
                    Money.ZERO, released.negate(), OpenTradeTerms.describe(TradeAction.EXP, held, multiplier, contract),
                    null));
        }
        ledger.correctFrozenChanges(accountId, corrections);
        // FEAT-039: an expiry only releases lots (no check); legacy lots may now be covered
        coverage.includeLegacy(accountId, contract.symbol());
        return new OptionTradeService.TradeView(expiry, entry, Optional.of(match));
    }

    /** New York "yyyy-MM-dd HH:mm", with seconds when they are not 0 (a 16:00:30 trade is later than 16:00). */
    private static String lateTime(Instant at) {
        LocalTime time = MarketTime.toLocal(at).toLocalTime();
        return time.getSecond() == 0 && time.getNano() == 0
                ? MarketTime.displayMinute(at)
                : DATE_HH_MM_SS.format(MarketTime.toLocal(at));
    }
}
