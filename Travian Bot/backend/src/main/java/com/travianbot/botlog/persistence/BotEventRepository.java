package com.travianbot.botlog.persistence;

import com.travianbot.botlog.domain.BotEvent;
import com.travianbot.botlog.domain.BotEventOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotEventRepository extends JpaRepository<BotEvent, Long> {

    Page<BotEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<BotEvent> findAllByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    Page<BotEvent> findAllByAccountIdAndOutcomeOrderByCreatedAtDesc(
            Long accountId, BotEventOutcome outcome, Pageable pageable);

    Page<BotEvent> findAllByOutcomeOrderByCreatedAtDesc(BotEventOutcome outcome, Pageable pageable);

    /** Used by the account-deletion listener to drop this account's log. */
    void deleteAllByAccountId(Long accountId);
}
