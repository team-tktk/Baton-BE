package com.baton.readiness.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** 보완 질문 답변. 답한 질문만 보내면 되고, 여러 번 나눠 보내도 된다(같은 질문은 마지막 답으로 바뀐다). */
public record FixAnswerRequest(
		@NotEmpty @Size(max = 50) List<@Valid Answer> answers) {

	public record Answer(
			@NotBlank String questionId,
			@NotBlank @Size(max = 2000) String answer) {
	}
}
