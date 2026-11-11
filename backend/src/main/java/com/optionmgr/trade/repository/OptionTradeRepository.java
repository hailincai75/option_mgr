package com.optionmgr.trade.repository;

import com.optionmgr.trade.domain.OptionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OptionTradeRepository extends JpaRepository<OptionTradeEntity, Long>,
        JpaSpecificationExecutor<OptionTradeEntity> {

    /** All trades of one contract (strike compared numerically by the database), in replay order. */
    List<OptionTradeEntity> findBySymbolAndExpirationDateAndStrikeAndOptionTypeOrderByTradedAtAscIdAsc(
            String symbol, LocalDate expirationDate, BigDecimal strike, OptionType optionType);
}
