package com.optionmgr.quote.repository;

import org.springframework.data.jpa.repository.JpaRepository;

/** FEAT-016. */
public interface UnderlyingQuoteRepository extends JpaRepository<UnderlyingQuoteEntity, String> {
}
