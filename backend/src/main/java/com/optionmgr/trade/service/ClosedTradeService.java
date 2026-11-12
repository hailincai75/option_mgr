package com.optionmgr.trade.service;

import com.optionmgr.common.money.Money;
import com.optionmgr.trade.domain.ClosedTrade;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.domain.TradeFilter;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import com.optionmgr.trade.repository.OptionTradeSpecifications;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-006: closed trades (one per BTC / STC) with their realized P&amp;L, derived on read by replaying the
 * contracts (FIFO, Q21/Q39). The filtered count and P&amp;L total cover all filtered closes, not just the page (Q46),
 * so every filtered close is loaded and matched per request - fine for a personal app's data volume.
 */
@Service
public class ClosedTradeService {

    static final Set<TradeAction> CLOSING_ACTIONS = EnumSet.of(TradeAction.BTC, TradeAction.STC);

    private final OptionTradeRepository trades;
    private final OptionTradeService tradeService;

    public ClosedTradeService(OptionTradeRepository trades, OptionTradeService tradeService) {
        this.trades = trades;
        this.tradeService = tradeService;
    }

    /** One closed trade: the stored trade view (ledger entry, match) and its derived figures. */
    public record ClosedTradeView(OptionTradeService.TradeView trade, ClosedTrade figures) {
    }

    public record ClosedTradePage(List<ClosedTradeView> items, int page, int size, long totalItems, int totalPages,
                                  BigDecimal filteredRealizedPnlTotal) {
    }

    @Transactional(readOnly = true)
    public ClosedTradePage page(TradeFilter filter, int page, int size) {
        List<ClosedTradeView> views = all(filter);
        BigDecimal total = views.stream().map(v -> v.figures().realizedPnl()).reduce(Money.ZERO, BigDecimal::add);

        long totalItems = views.size();
        int totalPages = (int) ((totalItems + size - 1) / size);
        long from = (long) (page - 1) * size;
        List<ClosedTradeView> items = from >= totalItems
                ? List.of()
                : views.subList((int) from, (int) Math.min(from + size, totalItems));
        return new ClosedTradePage(List.copyOf(items), page, size, totalItems, totalPages, total);
    }

    /** Every close matching {@code filter} (an empty action set = BTC and STC), newest first, with its figures. */
    @Transactional(readOnly = true)
    public List<ClosedTradeView> all(TradeFilter filter) {
        TradeFilter closesOnly = new TradeFilter(filter.symbol(), filter.expirationDate(), filter.strike(),
                filter.optionType(), filter.actions().isEmpty() ? CLOSING_ACTIONS : filter.actions(),
                filter.startDate(), filter.endDate());
        List<OptionTradeEntity> all = trades.findAll(OptionTradeSpecifications.matching(closesOnly),
                OptionTradeService.LIST_ORDER);
        List<ClosedTradeView> views = tradeService.views(all).stream()
                .map(v -> new ClosedTradeView(v, new ClosedTrade(v.trade().getAction(), v.trade().getContracts(),
                        v.ledgerEntry().getAmount(), v.closeMatch().orElseThrow(
                                () -> new IllegalStateException("close " + v.trade().getId() + " has no match")))))
                .toList();
        return views;
    }
}
