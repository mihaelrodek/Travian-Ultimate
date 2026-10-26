package com.travianbot.botlog.persistence;

import com.travianbot.botlog.domain.BotEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * List queries go through {@link JpaSpecificationExecutor} — the Log screen
 * filters on any combination of account, outcome, date range and free-text
 * search, and the derived-method-name approach explodes combinatorially past
 * two optional filters.
 */
public interface BotEventRepository extends JpaRepository<BotEvent, Long>, JpaSpecificationExecutor<BotEvent> {

    /** Used by the account-deletion listener to drop this account's log. */
    void deleteAllByAccountId(Long accountId);
}
