package com.optionmgr.cash.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashLedgerEntryRepository extends JpaRepository<CashLedgerEntryEntity, Long> {

    /** Every entry in replay order: by time, then entry order. */
    List<CashLedgerEntryEntity> findAllByOrderByOccurredAtAscIdAsc();
}
