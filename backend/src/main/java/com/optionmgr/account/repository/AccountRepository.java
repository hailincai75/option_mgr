package com.optionmgr.account.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    List<AccountEntity> findAllByOrderByIdAsc();

    Optional<AccountEntity> findByNameKey(String nameKey);
}
