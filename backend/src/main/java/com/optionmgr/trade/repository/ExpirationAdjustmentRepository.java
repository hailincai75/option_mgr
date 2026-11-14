package com.optionmgr.trade.repository;

import com.optionmgr.trade.domain.OptionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** FEAT-043 expiration adjustments. */
public interface ExpirationAdjustmentRepository extends JpaRepository<ExpirationAdjustmentEntity, Long> {

    /** The contract on {@code newExpirationDate} is the result of an adjustment (strike compared by the database). */
    boolean existsByAccountIdAndSymbolAndNewExpirationDateAndStrikeAndOptionType(Long accountId, String symbol,
                                                                                LocalDate newExpirationDate,
                                                                                BigDecimal strike,
                                                                                OptionType optionType);

    List<ExpirationAdjustmentEntity> findByAccountId(Long accountId);
}
