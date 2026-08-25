package com.baton.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank @Email String email,
		@NotBlank @Size(min = 8, max = 64) String password,
		@NotBlank @Size(max = 50) String name,
		@NotBlank @Size(max = 100) String team,
		@NotBlank @Size(max = 50) String position) {
}
