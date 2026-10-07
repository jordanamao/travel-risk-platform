package com.travelrisk.platform.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** A route an employee travels often, pinned on their profile for one-click checks. */
@Embeddable
public record FrequentRoute(
    @Column(nullable = false) String origin,
    @Column(nullable = false) String destination,
    @Column(nullable = false, length = 32) String mode) {}
