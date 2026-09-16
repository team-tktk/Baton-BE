package com.baton.readiness.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** 보완안 추가 질문 답변. 답한 질문만 보내면 된다. */
public record FixAnswerRequest(
		@NotEmpty @Size(max = 10) List<@Valid Answer> answers) {

	public record Answer(
			@NotBlank String questionId,
			@NotBlank @Size(max = 2000) String answer) {
	}
}
