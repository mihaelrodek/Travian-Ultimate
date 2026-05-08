package com.travianbot.hero.service;

import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.hero.api.dto.HeroResponse;
import com.travianbot.hero.domain.Hero;
import com.travianbot.hero.persistence.HeroRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side service for the hero feature. Loads the hero + its item/adventure
 * collections inside a single transaction and flattens them into a
 * {@link HeroResponse} before returning — necessary because
 * {@code spring.jpa.open-in-view: false} means lazy collections cannot be
 * touched from the controller layer.
 */
@Service
@Transactional(readOnly = true)
public class HeroService {

    private final HeroRepository heroRepository;

    public HeroService(HeroRepository heroRepository) {
        this.heroRepository = heroRepository;
    }

    public HeroResponse getHero(Long accountId) {
        Hero hero = heroRepository.findByAccountId(accountId)
                .orElseThrow(() -> new HeroNotFoundException(accountId));
        // Force-initialize the lazy collections by reading them inside this tx.
        hero.getItems().size();
        hero.getAdventures().size();
        return HeroResponse.from(hero);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        heroRepository.findByAccountId(event.accountId())
                .ifPresent(heroRepository::delete);
    }
}
