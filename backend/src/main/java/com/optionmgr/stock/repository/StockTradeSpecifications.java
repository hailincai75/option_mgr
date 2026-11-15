package com.optionmgr.stock.repository;

import com.optionmgr.stock.domain.StockTradeFilter;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** FEAT-011: the stock trade list filter as a JPA query (always within one account). */
public final class StockTradeSpecifications {

    private StockTradeSpecifications() {
    }

    public static Specification<StockTradeEntity> matching(long accountId, StockTradeFilter f) {
        return matching(List.of(accountId), f);
    }

    /** FEAT-046: records of any of {@code accountIds} matching {@code f} (the all-accounts list, one query). */
    public static Specification<StockTradeEntity> matching(Collection<Long> accountIds, StockTradeFilter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(accountIds.size() == 1
                    ? cb.equal(root.get("accountId"), accountIds.iterator().next())
                    : root.get("accountId").in(accountIds));
            if (f.symbol() != null) {
                p.add(cb.equal(root.get("symbol"), f.symbol()));
            }
            if (!f.types().isEmpty()) {
                p.add(root.get("type").in(f.types()));
            }
            f.tradedFrom().ifPresent(from -> p.add(cb.greaterThanOrEqualTo(root.<Instant>get("tradedAt"), from)));
            f.tradedBefore().ifPresent(to -> p.add(cb.lessThan(root.<Instant>get("tradedAt"), to)));
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
