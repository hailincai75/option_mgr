package com.optionmgr.quote.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** FEAT-016 Q142 close snapshots. */
public interface UnderlyingQuoteCloseRepository extends JpaRepository<UnderlyingQuoteCloseEntity, Long> {

    List<UnderlyingQuoteCloseEntity> findBySnapshotDate(LocalDate snapshotDate);
}
