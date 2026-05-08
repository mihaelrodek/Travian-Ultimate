package com.travianbot.hero.persistence;

import com.travianbot.hero.domain.Hero;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HeroRepository extends JpaRepository<Hero, Long> {
    Optional<Hero> findByAccountId(Long accountId);
}
