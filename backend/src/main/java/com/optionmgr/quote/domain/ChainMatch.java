package com.optionmgr.quote.domain;

import java.util.List;

/** FEAT-016 / FEAT-052: how a held contract was found in a successful chain. */
public sealed interface ChainMatch {

    /** In the CBOE chain. */
    record Found(CboeOption option) implements ChainMatch {
    }

    /** FEAT-052: in the Yahoo response of its expiration. */
    record FoundYahoo(YahooOption option) implements ChainMatch {
    }

    /** Not in the chain. */
    record Absent() implements ChainMatch {
    }

    /** Several different codes match the contract: not guessed, treated as not in the chain. */
    record Ambiguous(List<String> codes) implements ChainMatch {
    }

    default boolean found() {
        return this instanceof Found || this instanceof FoundYahoo;
    }
}
