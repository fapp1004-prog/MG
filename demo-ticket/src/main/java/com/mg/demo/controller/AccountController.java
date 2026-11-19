package com.mg.demo.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mg.demo.domain.Seat;
import com.mg.demo.repository.SeatRepository;
import com.mg.demo.security.LoginUser;

/** 화면이 쓰는 보조 API. 권한 정책 대상이 아니라서 MG에서는 「짝 없는 API」로 나온다. */
@RestController
@RequestMapping("/api")
public class AccountController {

    private final SeatRepository seatRepository;
    private final Environment environment;

    public AccountController(SeatRepository seatRepository, Environment environment) {
        this.seatRepository = seatRepository;
        this.environment = environment;
    }

    // 로그인한 내 정보 + 지금 서버가 취약 버전인지 고친 버전인지
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal LoginUser user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("displayName", user.getDisplayName());
        m.put("role", user.getRole());
        m.put("mode", List.of(environment.getActiveProfiles()).contains("fixed") ? "fixed" : "vuln");
        return m;
    }

    // 결제 화면용 좌석 목록
    @GetMapping("/seats")
    public List<Seat> seats() {
        return seatRepository.findAll();
    }
}
