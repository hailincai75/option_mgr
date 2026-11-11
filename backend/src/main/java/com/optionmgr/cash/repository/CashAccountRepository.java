package com.optionmgr.cash.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashAccountRepository extends JpaRepository<CashAccountEntity, Long> {

    /** SELECT ... FOR UPDATE on the account row; held until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CashAccountEntity a where a.id = :id")
    Optional<CashAccountEntity> lockById(@Param("id") long id);
}
