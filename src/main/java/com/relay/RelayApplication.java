package com.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// 이유: Heartbeat의 @Scheduled를 켠다. 이 설정이 없으면 @Scheduled는 아무 오류 없이 무시된다.
@EnableScheduling
public class RelayApplication {

	public static void main(String[] args) {
		SpringApplication.run(RelayApplication.class, args);
	}

}
