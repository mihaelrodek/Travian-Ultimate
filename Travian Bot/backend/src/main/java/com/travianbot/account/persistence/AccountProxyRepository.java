package com.travianbot.account.persistence;

import com.travianbot.account.domain.AccountProxy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountProxyRepository extends JpaRepository<AccountProxy, Long> {

    /** Oldest-first for a stable UI ordering — users see proxies in the order they added them. */
    List<AccountProxy> findByAccountIdOrderByCreatedAtAsc(Long accountId);

    /** Used by the service to locate the currently-active row so it can be unset before flipping a new one on. */
    Optional<AccountProxy> findByAccountIdAndActiveTrue(Long accountId);

    /** Cascade cleanup when an account is deleted. */
    void deleteAllByAccountId(Long accountId);
}
