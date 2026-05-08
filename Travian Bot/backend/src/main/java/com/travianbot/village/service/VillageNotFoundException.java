package com.travianbot.village.service;

import com.travianbot.common.error.NotFoundException;

public class VillageNotFoundException extends NotFoundException {
    public VillageNotFoundException(Long id) {
        super("Village " + id + " not found");
    }
}
