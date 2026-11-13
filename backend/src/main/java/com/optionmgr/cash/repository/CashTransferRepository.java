package com.optionmgr.cash.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashTransferRepository extends JpaRepository<CashTransferEntity, Long> {

    /** A transfer of the given account; one of another account is "not found" (FEAT-033). */
    Optional<CashTransferEntity> findByIdAndAccountId(Long id, Long accountId);
}
