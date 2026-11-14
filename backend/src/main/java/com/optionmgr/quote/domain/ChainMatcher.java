package com.optionmgr.quote.domain;

import com.optionmgr.trade.domain.ContractKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FEAT-016 rule 3: matches held contracts of one symbol against a chain by symbol + expiration + strike + call/put.
 * A code matches when its root equals or ends with the symbol; several different codes for one contract are
 * ambiguous. Pure.
 */
public final class ChainMatcher {

    private ChainMatcher() {
    }

    public static Map<ContractKey, ChainMatch> match(String symbol, CboeChain chain, Collection<ContractKey> contracts) {
        Map<ContractKey, List<CboeOption>> candidates = new HashMap<>();
        for (CboeOption o : chain.options()) {
            OccCode c = o.code();
            if (!c.rootMatches(symbol)) {
                continue;
            }
            ContractKey key = new ContractKey(symbol, c.expiration(), c.strike(), c.optionType());
            candidates.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }
        Map<ContractKey, ChainMatch> out = new LinkedHashMap<>();
        for (ContractKey k : contracts) {
            List<CboeOption> found = candidates.getOrDefault(k, List.of());
            List<String> codes = found.stream().map(o -> o.code().code()).distinct().toList();
            if (codes.isEmpty()) {
                out.put(k, new ChainMatch.Absent());
            } else if (codes.size() > 1) {
                out.put(k, new ChainMatch.Ambiguous(codes));
            } else {
                out.put(k, new ChainMatch.Found(found.get(0)));
            }
        }
        return out;
    }
}
