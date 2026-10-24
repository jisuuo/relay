package com.relay.message;

/**
 * 메시지만 보고 알 수 있는 실패(JSON 문법 오류, 모르는 type, 필수 필드 누락).
 *
 * <p>왜 하나의 예외로 감싸는가: 실패 원인은 Jackson의 여러 예외 타입으로 나온다.
 * 호출하는 쪽(핸들러)이 Jackson 세부 사항을 몰라도 "잘못된 메시지"라는 한 가지로 다룰 수 있게 한다.
 * 실패에 어떻게 대응할지(에러 응답, 연결 유지 여부)는 P1-5에서 정한다.
 */
public class InvalidMessageException extends RuntimeException {

	public InvalidMessageException(String message, Throwable cause) {
		super(message, cause);
	}

}
