package com.ayshriv.selfhostcrm.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: send name, description, or both. An empty description clears it. */
public record UpdateTeamRequest(
		@Size(max = 100, message = "Name is too long")
		@Pattern(regexp = ".*\\S.*", message = "Name must not be blank") String name,
		@Size(max = 500, message = "Description is too long") String description) {

	@AssertTrue(message = "Send name, description or both")
	public boolean isAnyFieldPresent() {
		return name != null || description != null;
	}

}
