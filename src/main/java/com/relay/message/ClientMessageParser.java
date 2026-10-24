package com.relay.message;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 텍스트 frame 문자열 → {@link ClientMessage} 변환.
 *
 * <p>왜 핸들러와 분리하는가: 변환과 판별은 서버 상태가 필요 없는 순수한 작업이다.
 * 분리하면 WebSocket 연결 없이 단위 테스트할 수 있고, 핸들러는 입출력만 담당한다.
 */
@Component
public class ClientMessageParser {

	// 이유: 처리에 필요 없는 모르는 필드("sender" 등)는 거절하지 않고 무시한다 (ADR-0007, Tolerant Reader).
	// Jackson 3.1.7 기본값도 무시였지만(테스트 시나리오 7로 확인), 정책을 기본값에 맡기지 않고 코드에 드러낸다.
	private final JsonMapper mapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	public ClientMessage parse(String text) {
		try {
			return mapper.readValue(text, ClientMessage.class);
		}
		catch (JacksonException e) {
			throw new InvalidMessageException("잘못된 메시지: " + e.getOriginalMessage(), e);
		}
	}

}
