package com.optionmgr.trade.web;

import com.optionmgr.account.domain.AccountRows;
import com.optionmgr.account.web.AccountScope;
import com.optionmgr.common.money.Money;
import com.optionmgr.common.time.MarketTime;
import com.optionmgr.quote.service.QuoteQueryService;
import com.optionmgr.quote.service.QuoteViews;
import com.optionmgr.trade.domain.AssignmentTerms;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.domain.CoverageStatus;
import com.optionmgr.trade.domain.CoverageTimeline;
import com.optionmgr.trade.domain.ExpirationStatus;
import com.optionmgr.trade.domain.ExpirationTimes;
import com.optionmgr.trade.domain.OptionPosition;
import com.optionmgr.trade.domain.OptionType;
import com.optionmgr.trade.domain.PositionDirection;
import com.optionmgr.trade.domain.PositionLot;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.service.OptionPositionService;
import com.optionmgr.valuation.domain.GreeksStatus;
import com.optionmgr.valuation.domain.RowGreeks;
import com.optionmgr.valuation.domain.RowValuation;
import com.optionmgr.valuation.domain.ValuationStatus;
import com.optionmgr.valuation.service.UnrealizedValuationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FEAT-003 option positions (read-only). */
@RestController
@RequestMapping("/api/option-positions")
public class OptionPositionController {

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final OptionPositionService service;
    private final QuoteQueryService quotes;

    public OptionPositionController(OptionPositionService service, QuoteQueryService quotes) {
        this.service = service;
        this.quotes = quotes;
    }

    public record PositionsResponse(LocalDate asOfDate, int positionCount, BigDecimal frozenTotal,
                                    List<PositionResponse> positions) {
    }

    public record PositionResponse(/* FEAT-035: the account of the row (current name) */
                                   long accountId, String accountName,
                                   String symbol, LocalDate expirationDate, String strike, OptionType optionType,
                                   String contractLabel, PositionDirection direction, int contracts,
                                   int multiplier, BigDecimal openingNetTotal, BigDecimal averageNetPerContract,
                                   BigDecimal frozenCash, long dte,
                                   /* FEAT-009 Q125: (asOfDate, expiration] trading days; 0 on the day; null once expired */
                                   Integer remainingTradingDays, ExpirationStatus expirationStatus,
                                   long daysPastExpiration, int lotCount, List<LotResponse> lots,
                                   TradeAction closeAction, List<Integer> allowedCloseContracts,
                                   /* FEAT-012: now < 16:00 New York on the expiration date */
                                   boolean closable,
                                   /* FEAT-012: 16:00 <= now < 17:30 on the expiration date ("到期待确认") */
                                   boolean expiryPending,
                                   /* FEAT-012: now >= 17:30 New York on the expiration date */
                                   boolean expirable,
                                   /* FEAT-012: contracts at the cutoff = the expiry size to send back */
                                   int expiryContracts,
                                   Instant expiryAt, String expiryAtLocal,
                                   /* FEAT-013: short put, any time (Q95); FEAT-041: also short call */
                                   boolean assignable, List<AssignChoice> assignChoices,
                                   LocalDate assignmentDateMax, LocalDate assignmentDateDefault,
                                   /* FEAT-041: short call rows: current shares of the underlying; null otherwise */
                                   Long underlyingShares,
                                   /* FEAT-039: short call rows only (null otherwise) */
                                   Long coveredShares, Integer uncoveredContracts, Long uncoveredShares,
                                   Long availableShares, Instant uncoveredShortfallAt,
                                   String uncoveredShortfallAtLocal, Long uncoveredShortfallHeldShares,
                                   /* FEAT-014: long rows, any time (Q81) */
                                   boolean exercisable, List<AssignChoice> exerciseChoices,
                                   LocalDate exerciseDateMax, LocalDate exerciseDateDefault,
                                   /* FEAT-014: long call rows: current available cash; null otherwise */
                                   BigDecimal availableCash,
                                   /* FEAT-042: expires on an ACTIVE ad-hoc closure ("临时休市" tag) */
                                   boolean expiresOnAdhocClosure, String adhocClosureEventName,
                                   /* FEAT-043: "调整到期日" button and its dialog data (0 / null when not adjustable) */
                                   boolean expirationAdjustable, int expirationAdjustmentRecordCount,
                                   LocalDate defaultNewExpirationDate, LocalDate newExpirationDateMin,
                                   LocalDate newExpirationDateMax,
                                   /* FEAT-016: quotes (one set for all accounts) */
                                   QuoteViews.UnderlyingQuote underlyingQuote, QuoteViews.OptionQuote optionQuote,
                                   /* FEAT-017: valued with optionQuote.mark (null unless unrealizedStatus = OK) */
                                   BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal unrealizedPnlPercent,
                                   ValuationStatus unrealizedStatus, String unrealizedLabel,
                                   /* FEAT-018: IV / Greeks from the stored CBOE quote (never null) */
                                   GreeksResponse greeks) {
    }

    /**
     * FEAT-018 {@code greeks} of a row (docs/api/FEAT-018.md): per-contract values scale 4 (CBOE, not flipped),
     * position values scale 2 (x contracts x multiplier x direction), IV percent scale 2; values {@code null} unless
     * status OK or when CBOE gave no value. No rho (Q167).
     */
    public record GreeksResponse(GreeksStatus status, String label, BigDecimal ivPercent,
                                 BigDecimal deltaPerContract, BigDecimal deltaPosition,
                                 BigDecimal gammaPerContract, BigDecimal gammaPosition,
                                 BigDecimal thetaPerContract, BigDecimal thetaPosition,
                                 BigDecimal vegaPerContract, BigDecimal vegaPosition,
                                 Instant quoteTime, String quoteTimeLocal, String manualMarkNote) {

        static GreeksResponse of(RowGreeks g) {
            return new GreeksResponse(g.status(), g.label(), g.ivPercent(),
                    g.delta().perContract(), g.delta().position(), g.gamma().perContract(), g.gamma().position(),
                    g.theta().perContract(), g.theta().position(), g.vega().perContract(), g.vega().position(),
                    g.quoteTime(),
                    g.quoteTime() == null ? null : LOCAL_SECONDS.format(MarketTime.toLocal(g.quoteTime())),
                    g.manualMarkNote());
        }
    }

    /** FEAT-013: one selectable assignment size (earliest whole lots) and its shares. */
    public record AssignChoice(int contracts, int shares) {
    }

    public record LotResponse(long tradeId, TradeAction action, Instant tradedAt, String tradedAtLocal,
                              int originalContracts, int remainingContracts, BigDecimal originalNetAmount,
                              BigDecimal remainingNetAmount, BigDecimal originalFrozen, BigDecimal remainingFrozen,
                              String note,
                              /* FEAT-039: short call lots only (null otherwise) */
                              CoverageStatus coverage, Long requiredShares, Instant shortfallAt,
                              String shortfallAtLocal, Long shortfallHeldShares,
                              /* FEAT-048: valued with the row's mark; status / label = the row's (Q239) */
                              BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal unrealizedPnlPercent,
                              ValuationStatus unrealizedStatus, String unrealizedLabel,
                              /* FEAT-051: the lot the close form selects by default (the earliest, rule 3) */
                              boolean closeDefault) {
    }

    @GetMapping
    public PositionsResponse positions(AccountScope scope) {
        // FEAT-035: each account computed on its own (one clock reading, one QuoteBook), rows never merged (Q215)
        List<OptionPositionService.PositionSummary> summaries = service.currentPositions(scope.ids());
        QuoteViews.QuoteBook book = quotes.book();
        List<AccountRows.Row<AccountPosition>> rows = new ArrayList<>();
        BigDecimal frozenTotal = Money.ZERO;
        for (int i = 0; i < summaries.size(); i++) {
            AccountScope.Member m = scope.accounts().get(i);
            OptionPositionService.PositionSummary s = summaries.get(i);
            frozenTotal = frozenTotal.add(s.frozenTotal());
            s.positions().forEach(p -> rows.add(new AccountRows.Row<>(m.id(), m.name(), new AccountPosition(p, s))));
        }
        List<PositionResponse> positions = AccountRows.sorted(rows,
                        Comparator.comparing(AccountPosition::position, OptionPosition.DISPLAY_ORDER)).stream()
                .map(r -> toResponse(r.accountId(), r.accountName(), r.value().position(), r.value().summary(), book))
                .toList();
        return new PositionsResponse(summaries.get(0).asOfDate(), positions.size(), frozenTotal, positions);
    }

    /** A position and the summary of its own account. */
    private record AccountPosition(OptionPosition position, OptionPositionService.PositionSummary summary) {
    }

    /** One row; also the "position" of the FEAT-043 adjustment response. */
    static PositionResponse toResponse(long accountId, String accountName, OptionPosition p,
                                       OptionPositionService.PositionSummary s,
                                       QuoteViews.QuoteBook book) {
        ContractKey c = p.contract();
        LocalDate today = s.asOfDate();
        Instant now = s.now();
        long dte = p.dte(today);
        Instant cutoff = ExpirationTimes.tradingCutoff(c.expirationDate());
        boolean assignable = p.direction() == PositionDirection.SHORT;
        boolean exercisable = p.direction() == PositionDirection.LONG;
        boolean longPut = exercisable && c.optionType() == OptionType.PUT;
        Long underlyingShares = (assignable && c.optionType() == OptionType.CALL) || longPut
                ? s.underlyingShares(p) : null;
        OptionPositionService.RowCoverage rc = s.coverage(p);
        Long availableShares = null;
        if (rc != null) {
            availableShares = rc.availableShares();
        } else if (longPut) {
            availableShares = s.availableShares(p);
        }
        LocalDate latestExercise = exercisable ? AssignmentTerms.latestDate(c.expirationDate(), now) : null;
        CoverageTimeline.Shortfall sf = rc == null ? null : rc.shortfall();
        LocalDate latest = assignable ? AssignmentTerms.latestDate(c.expirationDate(), now) : null;
        String adhocClosureName = s.adhocClosureEventName(p);
        OptionPositionService.Adjustability adj = s.adjustability(p);
        QuoteViews.OptionQuote optionQuote = book.option(c);
        RowValuation v = UnrealizedValuationService.option(p, today, optionQuote);
        List<RowValuation> lotValues = UnrealizedValuationService.optionLots(p, v, optionQuote);
        List<LotResponse> lots = new ArrayList<>(p.lots().size());
        for (int i = 0; i < p.lots().size(); i++) {
            lots.add(toResponse(p.lots().get(i), rc, lotValues.get(i), i == 0));
        }
        return new PositionResponse(accountId, accountName, c.symbol(), c.expirationDate(),
                ContractKey.strikeText(c.strike()),
                c.optionType(), c.label(), p.direction(), p.contracts(), p.multiplier(), p.openingNetTotal(),
                p.averageNetPerContract(), p.frozenCash(), dte, s.remainingTradingDays(p), p.expirationStatus(today), Math.max(0, -dte),
                p.lots().size(), lots,
                p.closeAction(), p.allowedCloseContracts(), p.closable(now), p.expiryPending(now), p.expirable(now),
                s.expiryContracts(p), cutoff, LOCAL_SECONDS.format(MarketTime.toLocal(cutoff)), assignable,
                assignable
                        ? p.allowedCloseContracts().stream().map(n -> new AssignChoice(n, n * p.multiplier())).toList()
                        : List.of(),
                latest, latest, underlyingShares,
                rc == null ? null : rc.coveredShares(), rc == null ? null : rc.uncoveredContracts(),
                rc == null ? null : rc.uncoveredShares(), availableShares,
                sf == null ? null : sf.at(), sf == null ? null : LOCAL_SECONDS.format(MarketTime.toLocal(sf.at())),
                sf == null ? null : sf.held(), exercisable,
                exercisable
                        ? p.allowedCloseContracts().stream().map(n -> new AssignChoice(n, n * p.multiplier())).toList()
                        : List.of(),
                latestExercise, latestExercise,
                exercisable && c.optionType() == OptionType.CALL ? s.availableCash() : null,
                adhocClosureName != null, adhocClosureName, adj != null, adj == null ? 0 : adj.recordCount(),
                adj == null ? null : adj.defaultDate(), adj == null ? null : adj.minDate(),
                adj == null ? null : adj.maxDate(), book.underlying(c.symbol()), optionQuote,
                v.marketValue(), v.unrealizedPnl(), v.percent(), v.status(), v.label(),
                GreeksResponse.of(UnrealizedValuationService.greeks(p, today, optionQuote, book.stored(c))));
    }

    private static LotResponse toResponse(PositionLot l, OptionPositionService.RowCoverage rc, RowValuation v,
                                          boolean closeDefault) {
        OptionPositionService.LotCoverage lc = rc == null ? null : rc.lots().get(l.tradeId());
        CoverageTimeline.Shortfall sf = lc == null ? null : lc.shortfall();
        return new LotResponse(l.tradeId(), l.action(), l.tradedAt(),
                LOCAL_SECONDS.format(MarketTime.toLocal(l.tradedAt())), l.originalContracts(),
                l.remainingContracts(), l.originalNetAmount(), l.remainingNetAmount(), l.originalFrozen(),
                l.remainingFrozen(), l.note(), lc == null ? null : lc.status(), lc == null ? null : lc.requiredShares(),
                sf == null ? null : sf.at(), sf == null ? null : LOCAL_SECONDS.format(MarketTime.toLocal(sf.at())),
                sf == null ? null : sf.held(), v.marketValue(), v.unrealizedPnl(), v.percent(), v.status(),
                v.label(), closeDefault);
    }
}
