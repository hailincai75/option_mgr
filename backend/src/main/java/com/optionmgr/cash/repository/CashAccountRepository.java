package com.optionmgr.cash.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashAccountRepository extends JpaRepository<CashAccountEntity, Long> {

    /** SELECT ... FOR UPDATE on one account's row; held until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CashAccountEntity a where a.id = :id")
    Optional<CashAccountEntity> lockById(@Param("id") long id);

    /** SELECT ... FOR UPDATE on every account row (e2e reset). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CashAccountEntity a order by a.id")
    List<CashAccountEntity> lockAll();
}
