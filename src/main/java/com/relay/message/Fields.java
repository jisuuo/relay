package com.relay.message;

/**
 * 메시지 record의 생성자에서 쓰는 필드 검사.
 */
final class Fields {

	private Fields() {
	}

	// 이유: null·""·공백만 있는 값은 처리할 수 없는 입력이라 모두 거절한다.
	// isBlank()는 ""와 공백만 있는 문자열을 함께 걸러 준다.
	// 내용이 있으면(" 안녕 ") 자르지 않고 그대로 둔다. 공백도 사용자가 보낸 내용의 일부다.
	static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + "은(는) 비어 있을 수 없다");
		}
		return value;
	}

}
