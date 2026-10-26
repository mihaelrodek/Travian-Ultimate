package com.travianbot.attackplan.persistence;

import com.travianbot.attackplan.domain.AttackPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttackPlanRepository extends JpaRepository<AttackPlan, Long> {
    List<AttackPlan> findAllByAccountIdOrderByCreatedAtDesc(Long accountId);

    Optional<AttackPlan> findByIdAndAccountId(Long id, Long accountId);

    void deleteAllByAccountId(Long accountId);
}
