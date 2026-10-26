package com.travianbot.account;

import com.travianbot.AbstractIntegrationTest;
import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class AccountRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    AccountRepository accounts;

    @Test
    void persistsAndReloadsAccount() {
        Account saved = accounts.save(new Account(
                "https://ts1.travian.com",
                "tester",
                "secret",
                "Main"
        ));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getNickname()).isEqualTo("Main");

        Account reloaded = accounts.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUsername()).isEqualTo("tester");
        assertThat(reloaded.getServerUrl()).isEqualTo("https://ts1.travian.com");
        assertThat(reloaded.isPlusAccountActive()).isFalse();
    }

    @Test
    void nicknameDefaultsToUsernameWhenBlank() {
        Account saved = accounts.save(new Account(
                "https://ts2.travian.com",
                "noNickname",
                "pw",
                "   "
        ));
        assertThat(saved.getNickname()).isEqualTo("noNickname");
    }
}
