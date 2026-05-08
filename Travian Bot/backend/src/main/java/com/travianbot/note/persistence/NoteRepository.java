package com.travianbot.note.persistence;

import com.travianbot.note.domain.Note;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteRepository extends JpaRepository<Note, Long> {
    List<Note> findAllByOrderByUpdatedAtDesc();
    List<Note> findAllByAccountIdOrderByUpdatedAtDesc(Long accountId);
    void deleteAllByAccountId(Long accountId);
}
