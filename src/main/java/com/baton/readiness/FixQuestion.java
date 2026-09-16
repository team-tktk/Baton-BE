package com.baton.readiness;

/** 자료에 답이 없을 때 인계자에게 묻는 추가 질문. answer가 null이면 아직 답하지 않은 것. */
public record FixQuestion(
		String id,
		String question,
		String reason,
		String answer) {

	public FixQuestion withAnswer(String answer) {
		return new FixQuestion(id, question, reason, answer);
	}

	/** is/get 접두어를 쓰지 않는다 — Jackson이 속성으로 읽어 jsonb·응답에 불필요한 필드가 생기지 않게. */
	public boolean hasAnswer() {
		return answer != null && !answer.isBlank();
	}
}
