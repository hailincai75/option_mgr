package com.optionmgr.cash.repository;

import com.optionmgr.cash.domain.CashSourceType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashLedgerEntryRepository extends JpaRepository<CashLedgerEntryEntity, Long> {

    /** Every entry in replay order: by time, then entry order. */
    List<CashLedgerEntryEntity> findAllByOrderByOccurredAtAscIdAsc();

    /** The (first) entry produced by an originating record. */
    Optional<CashLedgerEntryEntity> findFirstBySourceTypeAndSourceIdOrderByIdAsc(CashSourceType sourceType,
                                                                                 Long sourceId);

    /** Entries of several originating records of the given kinds, in entry order. */
    List<CashLedgerEntryEntity> findBySourceTypeInAndSourceIdInOrderByIdAsc(Collection<CashSourceType> sourceTypes,
                                                                           Collection<Long> sourceIds);
}
