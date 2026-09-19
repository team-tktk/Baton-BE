package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.baton.ai.dto.ChatAnswerResponse;
import com.baton.ai.dto.ChatMessageResponse;

class ChatConfirmationResponseTest {
	@Test
	void 충돌_표시는_즉시_응답과_대화_이력에_같이_노출된다() {
		ChatMessage message = ChatMessage.create(UUID.randomUUID(), UUID.randomUUID(), "마감일은?",
				"자료에는 9월 20일과 9월 21일이 함께 적혀 있어 담당자 확인 필요입니다.", true, true, List.of());
		message.onCreate();

		assertThat(ChatAnswerResponse.from(message).requiresConfirmation()).isTrue();
		assertThat(ChatMessageResponse.from(message).requiresConfirmation()).isTrue();
	}
}
