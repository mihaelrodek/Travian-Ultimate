package com.travianbot.intel.api.dto;

import java.time.Instant;

/** Single point on the per-village population history sparkline. */
public record VillagePopulationPointResponse(Instant capturedAt, int population) {}
