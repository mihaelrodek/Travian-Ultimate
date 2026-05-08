package com.travianbot.note.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.note.api.dto.NoteRequest;
import com.travianbot.note.domain.Note;
import com.travianbot.note.persistence.NoteRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class NoteService {

    private final NoteRepository repository;
    private final AccountRepository accountRepository;

    public NoteService(NoteRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<Note> list(Long accountId) {
        return (accountId == null)
                ? repository.findAllByOrderByUpdatedAtDesc()
                : repository.findAllByAccountIdOrderByUpdatedAtDesc(accountId);
    }

    @Transactional(readOnly = true)
    public Note get(Long id) {
        return repository.findById(id).orElseThrow(() -> new NoteNotFoundException(id));
    }

    public Note create(NoteRequest req) {
        if (req.accountId() == null) {
            throw new IllegalArgumentException("accountId is required to create a note");
        }
        Account account = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new AccountNotFoundException(req.accountId()));
        Note note = new Note(account, req.title().trim(), req.body());
        return repository.save(note);
    }

    public Note update(Long id, NoteRequest req) {
        Note note = get(id);
        note.update(req.title().trim(), req.body());
        return repository.save(note);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) throw new NoteNotFoundException(id);
        repository.deleteById(id);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }
}
