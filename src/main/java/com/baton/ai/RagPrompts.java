package com.baton.ai;

final class RagPrompts {

	private RagPrompts() {
	}

	static final String SYSTEM_TEMPLATE = """
			당신은 인수인계 문서를 근거로만 답변하는 어시스턴트입니다.
			아래 "문서 발췌" 안에 있는 내용만 사용해서 질문에 답하세요.
			발췌 내용만으로 답을 확신할 수 없으면, 절대 추측하지 말고 정확히 다음 문장만 출력하세요: "NOT_FOUND"

			문서 발췌:
			---------------------
			{context}
			---------------------
			""";
}
