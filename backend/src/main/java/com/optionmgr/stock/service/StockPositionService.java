package com.optionmgr.stock.service;

import com.optionmgr.stock.domain.StockPosition;
import com.optionmgr.stock.domain.StockReplay;
import com.optionmgr.stock.repository.StockTradeEntity;
import com.optionmgr.stock.repository.StockTradeRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FEAT-011 current stock positions of an account: a FIFO replay per symbol, nothing stored. */
@Service
public class StockPositionService {

    private final StockTradeRepository trades;

    public StockPositionService(StockTradeRepository trades) {
        this.trades = trades;
    }

    /** Symbols with shares &gt; 0, by symbol. */
    @Transactional(readOnly = true)
    public List<StockPosition> currentPositions(long accountId) {
        Map<String, List<com.optionmgr.stock.domain.StockRecord>> bySymbol = new LinkedHashMap<>();
        for (StockTradeEntity t : trades.findByAccountIdOrderByTradedAtAscIdAsc(accountId)) {
            bySymbol.computeIfAbsent(t.getSymbol(), s -> new ArrayList<>()).add(t.toRecord());
        }
        List<StockPosition> positions = new ArrayList<>();
        bySymbol.forEach((symbol, records) -> {
            StockReplay.History history = StockReplay.replay(records);
            if (!history.openLots().isEmpty()) {
                positions.add(new StockPosition(symbol, history.openLots()));
            }
        });
        positions.sort(StockPosition.DISPLAY_ORDER);
        return positions;
    }
}
