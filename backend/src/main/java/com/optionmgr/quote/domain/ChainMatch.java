package com.optionmgr.quote.domain;

import java.util.List;

/** FEAT-016: how a held contract was found in a successful chain. */
public sealed interface ChainMatch {

    record Found(CboeOption option) implements ChainMatch {
    }

    /** Not in the chain. */
    record Absent() implements ChainMatch {
    }

    /** Several different codes match the contract: not guessed, treated as not in the chain. */
    record Ambiguous(List<String> codes) implements ChainMatch {
    }
}
