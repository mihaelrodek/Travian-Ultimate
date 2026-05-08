package com.travianbot.account.service;

import com.travianbot.common.error.NotFoundException;

public class AccountNotFoundException extends NotFoundException {
    public AccountNotFoundException(Long id) {
        super("Account " + id + " not found");
    }
}
