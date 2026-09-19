package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

/**
 * 업로드 문서 내용에 중괄호({})가 들어있어도(코드·JSON 붙여넣기 등) 프롬프트 렌더링이 깨지지 않는지 확인.
 * 렌더링은 OpenAI 호출 전에 일어나므로 API 키 없이 검증 가능하다.
 */
class RagPromptsRenderTest {

	// 코드/JSON처럼 중괄호가 섞인, 아주 평범할 수 있는 문서 내용
	private static final String BRACY_TEXT =
			"설정 예시: {\"timeout\": 30, \"retries\": 3}\n자바 코드: if (x > 0) { doThing(); }";

	@Test
	void SYSTEM_TEMPLATE_문서에_중괄호가_있어도_렌더링된다() {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.SYSTEM_TEMPLATE);
		assertThatCode(() -> {
			Message message = template.createMessage(Map.of("context", BRACY_TEXT));
			assertThat(message.getText()).contains("timeout");
		}).doesNotThrowAnyException();
	}

	@Test
	void QUESTIONS_TEMPLATE_문서에_중괄호가_있어도_렌더링된다() {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.QUESTIONS_SYSTEM_TEMPLATE);
		assertThatCode(() -> template.createMessage(Map.of("documents", BRACY_TEXT, "askedQuestions", BRACY_TEXT)))
				.doesNotThrowAnyException();
	}

	@Test
	void SECTION_UPDATE_TEMPLATE_문서에_중괄호가_있어도_렌더링된다() {
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.SECTION_UPDATE_SYSTEM_TEMPLATE);
		assertThatCode(() -> template.createMessage(Map.of(
				"documents", BRACY_TEXT, "currentSections", BRACY_TEXT, "qna", BRACY_TEXT, "sections", "업무 일정(schedule)")))
				.doesNotThrowAnyException();
	}

	@Test
	void 짝이_안맞는_중괄호나_변수처럼_생긴_토큰이_있어도_렌더링된다() {
		// 템플릿 엔진이 제일 잘 깨지는 케이스: 짝 안 맞는 '{', 실제 변수명처럼 생긴 토큰
		String nasty = "여는 중괄호만: { 그리고 변수처럼 생긴 {context} {documents} 토큰";
		SystemPromptTemplate template = new SystemPromptTemplate(RagPrompts.SYSTEM_TEMPLATE);
		assertThatCode(() -> template.createMessage(Map.of("context", nasty)))
				.doesNotThrowAnyException();
	}
}
