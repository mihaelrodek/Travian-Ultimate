package com.travianbot.aianalysis.persistence;

import com.travianbot.aianalysis.domain.OasisScan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface OasisScanRepository extends JpaRepository<OasisScan, Long> {

    Optional<OasisScan> findFirstByAccountIdOrderByStartedAtDesc(Long accountId);

    List<OasisScan> findAllByAccountIdOrderByStartedAtDesc(Long accountId);

    @Modifying
    @Transactional
    @Query("delete from OasisScan s where s.account.id = ?1")
    void deleteAllByAccountId(Long accountId);
}
