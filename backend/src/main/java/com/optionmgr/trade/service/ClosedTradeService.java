package com.optionmgr.trade.service;

import com.optionmgr.common.money.Money;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.repository.StockTradeRepository;
import com.optionmgr.stock.repository.StockTradeSpecifications;
import com.optionmgr.stock.service.StockTradeService;
import com.optionmgr.trade.domain.ClosedRecordFilter;
import com.optionmgr.trade.domain.ClosedRecordKey;
import com.optionmgr.trade.domain.ClosedRecordType;
import com.optionmgr.trade.domain.ClosedStockSale;
import com.optionmgr.trade.domain.ClosedTrade;
import com.optionmgr.trade.domain.TradeAction;
import com.optionmgr.trade.domain.TradeFilter;
import com.optionmgr.trade.repository.OptionTradeEntity;
import com.optionmgr.trade.repository.OptionTradeRepository;
import com.optionmgr.trade.repository.OptionTradeSpecifications;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-006 / FEAT-040: closed records with their realized P&amp;L - option closes (BTC / STC) and expiries (EXP), and
 * (FEAT-040) stock sales - derived on read by replaying the contracts (FIFO, Q21/Q39) and the stock symbols
 * (FEAT-011 FIFO, Q69/Q87). The filtered count and P&amp;L total cover all filtered records, not just the page (Q46),
 * so every filtered record of both types is loaded, matched and merged into one order per request (pagination over
 * the merged list) - fine for a personal app's data volume.
 */
@Service
public class ClosedTradeService {

    /** Option records listed as closed trades: user closes and (FEAT-012) expiries. */
    public static final Set<TradeAction> CLOSING_ACTIONS = EnumSet.of(TradeAction.BTC, TradeAction.STC, TradeAction.EXP,
            TradeAction.ASG);

    private final OptionTradeRepository trades;
    private final OptionTradeService tradeService;
    private final StockTradeRepository stockTrades;
    private final StockTradeService stockTradeService;

    public ClosedTradeService(OptionTradeRepository trades, OptionTradeService tradeService,
                              StockTradeRepository stockTrades, StockTradeService stockTradeService) {
        this.trades = trades;
        this.tradeService = tradeService;
        this.stockTrades = stockTrades;
        this.stockTradeService = stockTradeService;
    }

    /** One closed record (option close / expiry or stock sale). */
    public sealed interface ClosedRecordView permits ClosedTradeView, StockSaleView {
        ClosedRecordKey key();

        String symbol();

        BigDecimal realizedPnl();
    }

    /** One option close / expiry: the stored trade view (ledger entry, match) and its derived figures. */
    public record ClosedTradeView(OptionTradeService.TradeView trade, ClosedTrade figures) implements ClosedRecordView {
        @Override
        public ClosedRecordKey key() {
            return new ClosedRecordKey(ClosedRecordType.OPTION, trade.trade().getId(), trade.trade().getTradedAt());
        }

        @Override
        public String symbol() {
            return trade.trade().getSymbol();
        }

        @Override
        public BigDecimal realizedPnl() {
            return figures.realizedPnl();
        }
    }

    /** One stock sale and its derived figures (FEAT-040). */
    public record StockSaleView(StockTradeEntity sale, ClosedStockSale figures) implements ClosedRecordView {
        @Override
        public ClosedRecordKey key() {
            return new ClosedRecordKey(ClosedRecordType.STOCK, sale.getId(), sale.getTradedAt());
        }

        @Override
        public String symbol() {
            return sale.getSymbol();
        }

        @Override
        public BigDecimal realizedPnl() {
            return figures.realizedPnl();
        }
    }

    public record ClosedTradePage(List<ClosedRecordView> items, int page, int size, long totalItems, int totalPages,
                                  BigDecimal filteredRealizedPnlTotal) {
    }

    @Transactional(readOnly = true)
    public ClosedTradePage page(long accountId, ClosedRecordFilter filter, int page, int size) {
        List<ClosedRecordView> views = all(accountId, filter);
        BigDecimal total = views.stream().map(ClosedRecordView::realizedPnl).reduce(Money.ZERO, BigDecimal::add);

        long totalItems = views.size();
        int totalPages = (int) ((totalItems + size - 1) / size);
        long from = (long) (page - 1) * size;
        List<ClosedRecordView> items = from >= totalItems
                ? List.of()
                : views.subList((int) from, (int) Math.min(from + size, totalItems));
        return new ClosedTradePage(List.copyOf(items), page, size, totalItems, totalPages, total);
    }

    /** Every record matching {@code filter}, newest first (same instant: stock sales first, Q92), with figures. */
    @Transactional(readOnly = true)
    public List<ClosedRecordView> all(long accountId, ClosedRecordFilter filter) {
        List<ClosedRecordView> views = new ArrayList<>();
        if (filter.includesOptions()) {
            views.addAll(optionCloses(accountId, filter.options()));
        }
        if (filter.includesStocks()) {
            views.addAll(stockSales(accountId, filter));
        }
        views.sort(Comparator.comparing(ClosedRecordView::key, ClosedRecordKey.NEWEST_FIRST));
        return views;
    }

    /** Option closes matching {@code filter} (an empty action set = BTC, STC and EXP). */
    private List<ClosedTradeView> optionCloses(long accountId, TradeFilter filter) {
        TradeFilter closesOnly = new TradeFilter(filter.symbol(), filter.expirationDate(), filter.strike(),
                filter.optionType(), filter.actions().isEmpty() ? CLOSING_ACTIONS : filter.actions(),
                filter.startDate(), filter.endDate());
        List<OptionTradeEntity> all = trades.findAll(OptionTradeSpecifications.matching(accountId, closesOnly),
                OptionTradeService.LIST_ORDER);
        return tradeService.views(accountId, all).stream()
                .map(v -> new ClosedTradeView(v, new ClosedTrade(v.trade().getAction(), v.trade().getContracts(),
                        // FEAT-013: an assignment's cash pays for the shares; its option close net is 0
                        v.trade().getAction() == TradeAction.ASG ? Money.ZERO : v.cashChange(), v.closeMatch().orElseThrow(
                                () -> new IllegalStateException("close " + v.trade().getId() + " has no match")))))
                .toList();
    }

    /** Stock sales matching the symbol / date range, with their FIFO match under the current history (FEAT-011). */
    private List<StockSaleView> stockSales(long accountId, ClosedRecordFilter filter) {
        List<StockTradeEntity> sales = stockTrades.findAll(
                StockTradeSpecifications.matching(accountId, filter.stockSales()));
        return stockTradeService.views(accountId, sales).stream()
                .map(v -> new StockSaleView(v.trade(), new ClosedStockSale(v.trade().getShares(),
                        v.trade().getAmount(), v.sale().orElseThrow(
                                () -> new IllegalStateException("stock sale " + v.trade().getId() + " has no match")))))
                .toList();
    }
}
