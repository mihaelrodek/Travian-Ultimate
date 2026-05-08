package com.travianbot.farmlist.service;

import com.travianbot.common.error.NotFoundException;

public class FarmlistNotFoundException extends NotFoundException {
    public FarmlistNotFoundException(Long id) {
        super("Farmlist " + id + " not found");
    }
}
