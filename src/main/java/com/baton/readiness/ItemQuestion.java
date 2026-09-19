package com.baton.readiness;

import java.util.List;

/**
 * 평가가 만든, 이 영역을 채우려면 인계자에게 물어야 하는 질문(평가 행의 JSON에 함께 저장).
 * 자료에 답이 있으면 만들지 않는다. 자료끼리 값이 다르면(충돌) "어느 쪽이 맞나요?"를 묻고, 자료의 값을 options로 준다.
 *
 * @param options 고를 수 있는 값. 자료에 근거가 없으면 빈 배열(자유 답변).
 */
public record ItemQuestion(
		String question,
		String reason,
		List<String> options) {
}
