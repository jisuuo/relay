package com.relay.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * P1-3 단일 스레드 동작: JOIN·LEAVE·끊김마다 두 매핑이 정의 단계의 표대로 바뀌는지 확인한다.
 * 동시 실행은 {@link RoomRegistryConcurrencyTest}에서 다룬다.
 */
class RoomRegistryTest {

	private final RoomRegistry registry = new RoomRegistry();
	private final WebSocketSession s = mock(WebSocketSession.class);
	private final WebSocketSession t = mock(WebSocketSession.class);

	@Test
	void 없는_방에_JOIN하면_방이_만들어지고_양쪽_매핑에_기록된다() {
		assertThat(registry.join(s, "A")).isTrue();

		assertThat(registry.roomsSnapshot()).isEqualTo(Map.of("A", Set.of(s)));
		assertThat(registry.roomsBySessionSnapshot()).isEqualTo(Map.of(s, Set.of("A")));
	}

	@Test
	void 이미_들어간_방에_다시_JOIN하면_아무_일도_없다() {
		registry.join(s, "A");

		assertThat(registry.join(s, "A")).isFalse();

		assertThat(registry.roomsSnapshot()).isEqualTo(Map.of("A", Set.of(s)));
		assertThat(registry.roomsBySessionSnapshot()).isEqualTo(Map.of(s, Set.of("A")));
	}

	@Test
	void 마지막_1명이_LEAVE하면_빈_방과_빈_세션_항목이_삭제된다() {
		registry.join(s, "A");

		assertThat(registry.leave(s, "A")).isTrue();

		assertThat(registry.roomsSnapshot()).isEmpty();
		assertThat(registry.roomsBySessionSnapshot()).isEmpty();
	}

	@Test
	void 다른_세션이_남아_있으면_LEAVE해도_방은_유지된다() {
		registry.join(s, "A");
		registry.join(t, "A");

		registry.leave(s, "A");

		assertThat(registry.roomsSnapshot()).isEqualTo(Map.of("A", Set.of(t)));
		assertThat(registry.roomsBySessionSnapshot()).isEqualTo(Map.of(t, Set.of("A")));
	}

	@Test
	void 방에_없는_세션이_LEAVE하면_false이고_상태는_그대로다() {
		registry.join(t, "A");

		assertThat(registry.leave(s, "A")).isFalse();
		assertThat(registry.leave(s, "없는방")).isFalse();

		assertThat(registry.roomsSnapshot()).isEqualTo(Map.of("A", Set.of(t)));
		assertThat(registry.roomsBySessionSnapshot()).isEqualTo(Map.of(t, Set.of("A")));
	}

	@Test
	void 연결이_끊기면_들어간_모든_방에서_제거되고_빈_방은_삭제된다() {
		// 정의 단계의 예: 방 A → {S, T}, 방 C → {S}
		registry.join(s, "A");
		registry.join(t, "A");
		registry.join(s, "C");

		registry.removeSession(s);

		assertThat(registry.roomsSnapshot()).isEqualTo(Map.of("A", Set.of(t)));
		assertThat(registry.roomsBySessionSnapshot()).isEqualTo(Map.of(t, Set.of("A")));
	}

	@Test
	void 방에_들어가지_않은_세션이_끊겨도_아무_일도_없다() {
		registry.join(t, "A");

		registry.removeSession(s);

		assertThat(registry.roomsSnapshot()).isEqualTo(Map.of("A", Set.of(t)));
	}

}
