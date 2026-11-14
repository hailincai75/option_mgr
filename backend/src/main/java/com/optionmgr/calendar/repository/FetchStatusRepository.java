package com.optionmgr.calendar.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface FetchStatusRepository extends JpaRepository<FetchStatusEntity, Short> {

    /** The single status row, locked: fetches (startup, daily, test support) are serialized on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FetchStatusEntity s where s.id = 1")
    Optional<FetchStatusEntity> lockSingleRow();
}
