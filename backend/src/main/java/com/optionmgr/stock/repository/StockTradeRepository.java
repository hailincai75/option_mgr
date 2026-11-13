package com.optionmgr.stock.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StockTradeRepository extends JpaRepository<StockTradeEntity, Long>,
        JpaSpecificationExecutor<StockTradeEntity> {

    /** A record of the given account; one of another account is "not found" (FEAT-033). */
    Optional<StockTradeEntity> findByIdAndAccountId(Long id, Long accountId);

    /** All records of one symbol in one account, replay order. */
    List<StockTradeEntity> findByAccountIdAndSymbolOrderByTradedAtAscIdAsc(Long accountId, String symbol);

    /** All records of an account, replay order. */
    List<StockTradeEntity> findByAccountIdOrderByTradedAtAscIdAsc(Long accountId);
}
