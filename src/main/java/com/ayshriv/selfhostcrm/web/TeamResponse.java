package com.ayshriv.selfhostcrm.web;

import java.time.Instant;

/** A team with its number of visible (live, enabled) members. */
public record TeamResponse(Long id, String name, String description, Instant createdOn, long memberCount) {
}
