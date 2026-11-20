package com.optionmgr.quote.domain;

import com.optionmgr.trade.domain.ContractKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * FEAT-016 rule 3 (also FEAT-052 Yahoo): matches held contracts of one symbol against a chain by symbol + expiration
 * + strike + call/put. A code matches when its root equals or ends with the symbol; several different codes for one
 * contract are ambiguous. Pure.
 */
public final class ChainMatcher {

    private ChainMatcher() {
    }

    public static Map<ContractKey, ChainMatch> match(String symbol, CboeChain chain, Collection<ContractKey> contracts) {
        return match(symbol, chain.options(), CboeOption::code, ChainMatch.Found::new, contracts);
    }

    /** FEAT-052: the contracts (of the response's expiration) against one Yahoo response. */
    public static Map<ContractKey, ChainMatch> matchYahoo(String symbol, YahooChain chain,
                                                          Collection<ContractKey> contracts) {
        return match(symbol, chain.options(), YahooOption::code, ChainMatch.FoundYahoo::new, contracts);
    }

    private static <T> Map<ContractKey, ChainMatch> match(String symbol, List<T> options, Function<T, OccCode> code,
                                                          Function<T, ChainMatch> found,
                                                          Collection<ContractKey> contracts) {
        Map<ContractKey, List<T>> candidates = new HashMap<>();
        for (T o : options) {
            OccCode c = code.apply(o);
            if (!c.rootMatches(symbol)) {
                continue;
            }
            ContractKey key = new ContractKey(symbol, c.expiration(), c.strike(), c.optionType());
            candidates.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }
        Map<ContractKey, ChainMatch> out = new LinkedHashMap<>();
        for (ContractKey k : contracts) {
            List<T> list = candidates.getOrDefault(k, List.of());
            List<String> codes = list.stream().map(o -> code.apply(o).code()).distinct().toList();
            if (codes.isEmpty()) {
                out.put(k, new ChainMatch.Absent());
            } else if (codes.size() > 1) {
                out.put(k, new ChainMatch.Ambiguous(codes));
            } else {
                out.put(k, found.apply(list.get(0)));
            }
        }
        return out;
    }
}
