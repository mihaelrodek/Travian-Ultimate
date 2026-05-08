package com.travianbot.task.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.task.api.dto.TaskRequest;
import com.travianbot.task.domain.BotTask;
import com.travianbot.task.domain.TaskStatus;
import com.travianbot.task.persistence.BotTaskRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class BotTaskService {

    private final BotTaskRepository repository;
    private final AccountRepository accountRepository;

    public BotTaskService(BotTaskRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<BotTask> list(Long accountId, TaskStatus status) {
        if (accountId != null && status != null) {
            return repository.findAllByAccountIdAndStatusOrderByCreatedAtDesc(accountId, status);
        }
        if (accountId != null) return repository.findAllByAccountIdOrderByCreatedAtDesc(accountId);
        if (status != null) return repository.findAllByStatusOrderByCreatedAtDesc(status);
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public BotTask get(Long id) {
        return repository.findById(id).orElseThrow(() -> new BotTaskNotFoundException(id));
    }

    public BotTask create(TaskRequest req) {
        if (req.accountId() == null) {
            throw new IllegalArgumentException("accountId is required to create a task");
        }
        Account account = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new AccountNotFoundException(req.accountId()));
        BotTask task = new BotTask(account, req.type().trim(), req.description(), req.scheduledAt());
        return repository.save(task);
    }

    public BotTask update(Long id, TaskRequest req) {
        BotTask task = get(id);
        task.update(req.type().trim(), req.description(), req.scheduledAt());
        if (req.status() != null && req.status() != task.getStatus()) {
            task.markStatus(req.status(), req.resultMessage());
        } else if (req.resultMessage() != null) {
            task.markStatus(task.getStatus(), req.resultMessage());
        }
        return repository.save(task);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) throw new BotTaskNotFoundException(id);
        repository.deleteById(id);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }
}
