package com.mg.demo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * 로그인만 확인하고, "누가 무엇을 해도 되는지"는 각 API가 직접 검사해야 하는 구조.
 * 취약 버전 컨트롤러는 그 검사를 빠뜨렸다. (@EnableMethodSecurity가 켜져 있어야 고친 버전의 @PreAuthorize가 동작한다)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 시연 편의를 위해 CSRF 보호를 끔. 실제 서비스에서는 켜야 한다.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/app.js", "/style.css", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/api/concerts").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.defaultSuccessUrl("/", true).permitAll())
                .httpBasic(Customizer.withDefaults())
                .logout(logout -> logout.logoutSuccessUrl("/").permitAll())
                // /api/** 는 로그인 안 했을 때 로그인 페이지로 보내지 않고 401을 돌려준다
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        request -> request.getRequestURI().startsWith("/api/")));
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return new DemoUserDetailsService();
    }
}
