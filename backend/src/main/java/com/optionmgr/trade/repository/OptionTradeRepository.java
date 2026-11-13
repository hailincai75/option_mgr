package com.optionmgr.trade.repository;

import com.optionmgr.trade.domain.OptionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OptionTradeRepository extends JpaRepository<OptionTradeEntity, Long>,
        JpaSpecificationExecutor<OptionTradeEntity> {

    /** All trades of one contract in one account (strike compared numerically by the database), replay order. */
    List<OptionTradeEntity> findByAccountIdAndSymbolAndExpirationDateAndStrikeAndOptionTypeOrderByTradedAtAscIdAsc(
            Long accountId, String symbol, LocalDate expirationDate, BigDecimal strike, OptionType optionType);

    /** A trade of the given account; one of another account is "not found" (FEAT-033). */
    Optional<OptionTradeEntity> findByIdAndAccountId(Long id, Long accountId);

    /** All trades of an account in replay order. */
    List<OptionTradeEntity> findByAccountIdOrderByTradedAtAscIdAsc(Long accountId);
}
