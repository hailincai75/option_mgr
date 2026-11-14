package com.optionmgr.calendar.repository;

import com.optionmgr.calendar.domain.AdhocClosureStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdhocClosureRepository extends JpaRepository<AdhocClosureEntity, LocalDate> {

    List<AdhocClosureEntity> findAllByOrderByClosureDateAsc();

    List<AdhocClosureEntity> findByStatusOrderByClosureDateAsc(AdhocClosureStatus status);
}
