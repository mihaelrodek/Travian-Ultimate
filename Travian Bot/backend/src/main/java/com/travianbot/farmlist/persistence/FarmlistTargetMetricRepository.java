package com.travianbot.farmlist.persistence;

import com.travianbot.farmlist.domain.FarmlistTargetMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FarmlistTargetMetricRepository extends JpaRepository<FarmlistTargetMetric, Long> {

    /** One metric row per target — unique constraint makes this safe to use. */
    Optional<FarmlistTargetMetric> findFirstByFarmlistTargetId(Long farmlistTargetId);

    /** Batch lookup for the analytics UI — avoids N+1 across a farmlist's targets. */
    List<FarmlistTargetMetric> findAllByFarmlistTargetIdIn(Collection<Long> ids);
}
