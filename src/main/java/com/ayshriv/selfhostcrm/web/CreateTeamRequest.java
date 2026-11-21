package com.ayshriv.selfhostcrm.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTeamRequest(
		@NotBlank(message = "Name is required") @Size(max = 100, message = "Name is too long") String name,
		@Size(max = 500, message = "Description is too long") String description) {
}
