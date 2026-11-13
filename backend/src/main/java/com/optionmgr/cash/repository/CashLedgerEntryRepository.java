package com.optionmgr.cash.repository;

import com.optionmgr.cash.domain.CashSourceType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashLedgerEntryRepository extends JpaRepository<CashLedgerEntryEntity, Long> {

    /** Every entry of an account in replay order: by time, then entry order. */
    List<CashLedgerEntryEntity> findAllByAccountIdOrderByOccurredAtAscIdAsc(Long accountId);

    /** Entries of an account's originating records of the given kinds, in entry order. */
    List<CashLedgerEntryEntity> findByAccountIdAndSourceTypeInAndSourceIdInOrderByIdAsc(
            Long accountId, Collection<CashSourceType> sourceTypes, Collection<Long> sourceIds);
}
