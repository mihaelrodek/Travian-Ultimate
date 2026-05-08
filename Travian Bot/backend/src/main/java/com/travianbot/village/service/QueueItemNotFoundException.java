package com.travianbot.village.service;

import com.travianbot.common.error.NotFoundException;

public class QueueItemNotFoundException extends NotFoundException {
    public QueueItemNotFoundException(Long id) {
        super("Building queue item " + id + " not found");
    }
}
