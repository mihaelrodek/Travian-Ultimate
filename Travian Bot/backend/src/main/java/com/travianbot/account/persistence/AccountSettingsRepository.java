package com.travianbot.account.persistence;

import com.travianbot.account.domain.AccountSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountSettingsRepository extends JpaRepository<AccountSettings, Long> {

    Optional<AccountSettings> findByAccountId(Long accountId);
}
