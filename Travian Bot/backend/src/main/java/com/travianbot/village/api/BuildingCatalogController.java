package com.travianbot.village.api;

import com.travianbot.village.domain.BuildingCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exposes the static gid → building-name catalog so the frontend can render building
 * names and pick valid options in the build-queue editor without duplicating the list.
 */
@RestController
@RequestMapping("/api/buildings")
public class BuildingCatalogController {

    public record CatalogEntry(int gid, String name) {}

    @GetMapping("/catalog")
    public List<CatalogEntry> catalog() {
        List<CatalogEntry> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : BuildingCatalog.all().entrySet()) {
            out.add(new CatalogEntry(e.getKey(), e.getValue()));
        }
        return out;
    }
}
