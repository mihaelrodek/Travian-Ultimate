package com.travianbot.template.service;

import com.travianbot.common.error.NotFoundException;

public class VillageTemplateNotFoundException extends NotFoundException {
    public VillageTemplateNotFoundException(Long id) {
        super("Village template " + id + " not found");
    }
}
