package com.optionmgr.trade.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** FEAT-051: stored lot selections of BTC / STC trades. */
public interface OptionCloseLotRepository extends JpaRepository<OptionCloseLotEntity, OptionCloseLotEntity.Key> {

    List<OptionCloseLotEntity> findByAccountId(long accountId);

    List<OptionCloseLotEntity> findByCloseTradeIdIn(Collection<Long> closeTradeIds);

    Optional<OptionCloseLotEntity> findByLotTradeId(long lotTradeId);
}
