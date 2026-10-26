package com.travianbot.server.persistence;

import com.travianbot.server.domain.Server;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerRepository extends JpaRepository<Server, Long> {

    /** Lookup by canonical url (caller is responsible for normalising). */
    Optional<Server> findByUrl(String url);

    /** Picker view — only active rows, alphabetically by name. */
    List<Server> findAllByActiveTrueOrderByNameAsc();

    /** Admin view — every row, including soft-disabled. */
    List<Server> findAllByOrderByNameAsc();
}
