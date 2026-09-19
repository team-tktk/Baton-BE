package com.baton.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

/** 프롬프트 템플릿이 모든 변수로 렌더링되는지(자리표시자 누락·문법 오류는 AI 호출 때가 아니라 여기서 잡는다). */
class ReadinessPromptsTest {

	@Test
	void evaluateTemplateRenders() {
		String text = new SystemPromptTemplate(ReadinessPrompts.EVALUATE_SYSTEM_TEMPLATE).createMessage(Map.of(
				"criteria", "- EXCEPTION / 예외 대응 / 기준 / 업무 기준과 예외(RULES_AND_EXCEPTIONS)",
				"draft", "{\"purpose\":\"목적\"}",
				"sources", "- a.pdf",
				"documents", "원문")).getText();

		assertThat(text).contains("확인된 업무 기준", "targetSections", "questions", "업무 기준과 예외(RULES_AND_EXCEPTIONS)")
				.doesNotContain("%l", "{criteria}");
	}

	@Test
	void fixTemplateRenders() {
		String text = new SystemPromptTemplate(ReadinessPrompts.FIX_SYSTEM_TEMPLATE).createMessage(Map.of(
				"areas", "- EXCEPTION / 예외 대응",
				"currentValues", "- 업무 기준과 예외(rulesAndExceptions): []",
				"draft", "{}",
				"excerpts", "(관련 자료를 찾지 못함)",
				"answers", "(답변 없음)")).getText();

		assertThat(text).contains("자료 중 하나를 스스로 고르지 마세요", "confirmedCriteria")
				.doesNotContain("%l", "%s", "{areas}");
	}
}
