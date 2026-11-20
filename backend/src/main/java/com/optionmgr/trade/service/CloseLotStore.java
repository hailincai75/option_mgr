package com.optionmgr.trade.service;

import com.optionmgr.trade.repository.OptionCloseLotEntity;
import com.optionmgr.trade.repository.OptionCloseLotRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

/** FEAT-051 (Q255): the stored lot selection of every BTC / STC (close trade id -&gt; selected opening trade ids). */
@Component
public class CloseLotStore {

    private final OptionCloseLotRepository rows;

    public CloseLotStore(OptionCloseLotRepository rows) {
        this.rows = rows;
    }

    /** Every stored selection of the account. */
    public Map<Long, List<Long>> byAccount(long accountId) {
        return group(rows.findByAccountId(accountId));
    }

    /** The stored selections of the given closes (closes without rows are absent). */
    public Map<Long, List<Long>> forCloses(Collection<Long> closeTradeIds) {
        return closeTradeIds.isEmpty() ? Map.of() : group(rows.findByCloseTradeIdIn(closeTradeIds));
    }

    /** The close that selected the given open, if any. */
    public Optional<Long> closeSelecting(long lotTradeId) {
        return rows.findByLotTradeId(lotTradeId).map(OptionCloseLotEntity::getCloseTradeId);
    }

    /** Stores (or replaces) the selection of a close. */
    public void replace(long accountId, long closeTradeId, Collection<Long> lotTradeIds) {
        java.util.Set<Long> wanted = new java.util.LinkedHashSet<>(lotTradeIds);
        List<OptionCloseLotEntity> existing = rows.findByCloseTradeIdIn(List.of(closeTradeId));
        java.util.Set<Long> kept = new java.util.HashSet<>();
        for (OptionCloseLotEntity e : existing) {
            if (wanted.contains(e.getLotTradeId())) {
                kept.add(e.getLotTradeId());
            } else {
                rows.delete(e);
            }
        }
        // removals first: a lot may only be selected once (unique lot_trade_id)
        rows.flush();
        for (Long lot : wanted) {
            if (!kept.contains(lot)) {
                rows.save(new OptionCloseLotEntity(closeTradeId, lot, accountId));
            }
        }
        rows.flush();
    }

    /** Removes the selection of a close (before the close itself is deleted). */
    public void delete(long closeTradeId) {
        rows.deleteAll(rows.findByCloseTradeIdIn(List.of(closeTradeId)));
        rows.flush();
    }

    private static Map<Long, List<Long>> group(List<OptionCloseLotEntity> list) {
        Map<Long, List<Long>> out = new HashMap<>();
        list.forEach(e -> out.computeIfAbsent(e.getCloseTradeId(), k -> new ArrayList<>()).add(e.getLotTradeId()));
        return out;
    }
}
