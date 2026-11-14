package com.optionmgr.calendar.service;

import com.optionmgr.calendar.domain.AdhocClosure;
import com.optionmgr.calendar.domain.AdhocClosureStatus;
import com.optionmgr.calendar.repository.AdhocClosureEntity;
import com.optionmgr.calendar.repository.AdhocClosureRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEAT-042: the detected ad-hoc closures (one set for all accounts, Q53). Detection itself runs inside
 * {@link HolidayFetchService} (same transaction as the merge). FEAT-043 reuses {@link #findActive}.
 */
@Service
public class AdhocClosureService {

    private final AdhocClosureRepository closures;

    public AdhocClosureService(AdhocClosureRepository closures) {
        this.closures = closures;
    }

    /** All records by date, optionally only one status. */
    @Transactional(readOnly = true)
    public List<AdhocClosure> list(Optional<AdhocClosureStatus> status) {
        List<AdhocClosureEntity> rows = status.map(closures::findByStatusOrderByClosureDateAsc)
                .orElseGet(closures::findAllByOrderByClosureDateAsc);
        return rows.stream().map(AdhocClosureEntity::toDomain).toList();
    }

    /** ACTIVE closures: date to eventName, by date. */
    @Transactional(readOnly = true)
    public Map<LocalDate, String> activeClosures() {
        Map<LocalDate, String> active = new LinkedHashMap<>();
        list(Optional.of(AdhocClosureStatus.ACTIVE)).forEach(c -> active.put(c.date(), c.eventName()));
        return active;
    }

    /** The ACTIVE ad-hoc closure on {@code date}, if any. */
    @Transactional(readOnly = true)
    public Optional<AdhocClosure> findActive(LocalDate date) {
        return closures.findById(date).map(AdhocClosureEntity::toDomain).filter(AdhocClosure::active);
    }
}
