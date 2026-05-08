package com.travianbot.task.service;

import com.travianbot.common.error.NotFoundException;

public class BotTaskNotFoundException extends NotFoundException {
    public BotTaskNotFoundException(Long id) {
        super("Task " + id + " not found");
    }
}
