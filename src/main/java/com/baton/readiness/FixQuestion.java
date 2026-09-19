package com.baton.readiness;

import java.util.List;
import java.util.UUID;

/**
 * 보완안에서 인계자에게 묻는 질문. answer가 null이면 아직 답하지 않은 것.
 *
 * @param id                      보완안 안에서 유일한 id(q1, q2, …). 답변 요청에 쓴다.
 * @param area                    질문이 속한 영역.
 * @param options                 고를 수 있는 값. 없으면 빈 배열(자유 답변).
 * @param clarificationQuestionId 확인 질문 단계에서 "나중에 답하기"로 미룬 질문이면 그 id. 보완안을 적용할 때 그 질문도 답변 처리한다.
 */
public record FixQuestion(
		String id,
		ReadinessArea area,
		String question,
		String reason,
		List<String> options,
		UUID clarificationQuestionId,
		String answer) {

	public FixQuestion withId(String id) {
		return new FixQuestion(id, area, question, reason, options, clarificationQuestionId, answer);
	}

	public FixQuestion withAnswer(String answer) {
		return new FixQuestion(id, area, question, reason, options, clarificationQuestionId, answer);
	}

	/** is/get 접두어를 쓰지 않는다 — Jackson이 속성으로 읽어 jsonb·응답에 불필요한 필드가 생기지 않게. */
	public boolean hasAnswer() {
		return answer != null && !answer.isBlank();
	}

	public List<String> optionList() {
		return options == null ? List.of() : options;
	}
}
