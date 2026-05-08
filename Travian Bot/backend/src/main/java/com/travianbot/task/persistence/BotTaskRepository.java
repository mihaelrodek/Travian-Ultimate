package com.travianbot.task.persistence;

import com.travianbot.task.domain.BotTask;
import com.travianbot.task.domain.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotTaskRepository extends JpaRepository<BotTask, Long> {
    List<BotTask> findAllByOrderByCreatedAtDesc();
    List<BotTask> findAllByAccountIdOrderByCreatedAtDesc(Long accountId);
    List<BotTask> findAllByStatusOrderByCreatedAtDesc(TaskStatus status);
    List<BotTask> findAllByAccountIdAndStatusOrderByCreatedAtDesc(Long accountId, TaskStatus status);
    void deleteAllByAccountId(Long accountId);
}
