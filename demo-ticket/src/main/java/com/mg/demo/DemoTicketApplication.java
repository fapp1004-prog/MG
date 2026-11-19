package com.mg.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MG 시연용 예매 서비스.
 * 기본 실행은 권한 검사가 빠진 취약 버전, --spring.profiles.active=fixed 로 실행하면 고친 버전.
 */
@SpringBootApplication
public class DemoTicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoTicketApplication.class, args);
    }
}
