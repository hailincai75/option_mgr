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

    /**
     * Checks in the order of the API contract (time, position at the cutoff, same contracts, no late trades, cash)
     * and saves the expiry (+ ledger entry if it releases frozen cash); any failure throws {@link ApiException} and
     * nothing is saved.
     */
    @Transactional
    public OptionTradeService.TradeView expire(long accountId, ExpireCommand cmd) {
        ContractKey contract = cmd.contract();
        Instant cutoff = ExpirationTimes.tradingCutoff(contract.expirationDate());

        // 2. Q10: from 17:30 New York on the expiration date
        if (!ExpirationTimes.expirable(contract.expirationDate(), clock.instant())) {
            throw new ApiException(ErrorCode.EXPIRY_TOO_EARLY,
                    "到期日（" + contract.expirationDate() + "）美东 17:30 起才能作废");
        }

        ledger.lockAccount(accountId);
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
