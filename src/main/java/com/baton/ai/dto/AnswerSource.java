package com.baton.ai.dto;

/** AI 답변이 어디서 나왔는지: 업로드 문서 근거(DOCUMENT), 자료엔 없지만 일반 지식으로 답한 것(GENERAL_KNOWLEDGE), 둘 다 못 찾음(NOT_FOUND). */
public enum AnswerSource {
	DOCUMENT,
	GENERAL_KNOWLEDGE,
	NOT_FOUND
}
