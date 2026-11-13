package com.optionmgr.trade.repository;

import com.optionmgr.trade.domain.TradeFilter;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** FEAT-002: the trade list filter as a JPA query. */
public final class OptionTradeSpecifications {

    private OptionTradeSpecifications() {
    }

    /** Trades of {@code accountId} matching {@code f} (FEAT-033: always within one account). */
    public static Specification<OptionTradeEntity> matching(long accountId, TradeFilter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("accountId"), accountId));
            if (f.symbol() != null) {
                p.add(cb.equal(root.get("symbol"), f.symbol()));
            }
            if (f.expirationDate() != null) {
                p.add(cb.equal(root.get("expirationDate"), f.expirationDate()));
            }
            if (f.strike() != null) {
                // NUMERIC column: the database compares by value (200 = 200.000)
                p.add(cb.equal(root.get("strike"), f.strike()));
            }
            if (f.optionType() != null) {
                p.add(cb.equal(root.get("optionType"), f.optionType()));
            }
            if (!f.actions().isEmpty()) {
                p.add(root.get("action").in(f.actions()));
            }
            f.tradedFrom().ifPresent(from -> p.add(cb.greaterThanOrEqualTo(root.<Instant>get("tradedAt"), from)));
            f.tradedBefore().ifPresent(to -> p.add(cb.lessThan(root.<Instant>get("tradedAt"), to)));
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
