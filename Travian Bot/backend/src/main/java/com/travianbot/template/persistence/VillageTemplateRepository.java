package com.travianbot.template.persistence;

import com.travianbot.template.domain.VillageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VillageTemplateRepository extends JpaRepository<VillageTemplate, Long> {

    /**
     * Ordered newest-updated-first so the Templates page shows "what I was
     * tweaking most recently" at the top — mirrors {@link com.travianbot.note.persistence.NoteRepository}.
     */
    List<VillageTemplate> findAllByAccountIdOrderByUpdatedAtDesc(Long accountId);

    /**
     * Used to surface a soft uniqueness warning when the user imports a CSV
     * under a name that already exists on the same account. Names aren't
     * strictly unique at the DB level (the composite index is not UNIQUE) —
     * duplicates are allowed so the user can park a slightly-tweaked copy
     * without being blocked.
     */
    Optional<VillageTemplate> findFirstByAccountIdAndName(Long accountId, String name);

    /** Account-cascade cleanup — see {@code VillageTemplateService#onAccountDeleting}. */
    void deleteAllByAccountId(Long accountId);
}
