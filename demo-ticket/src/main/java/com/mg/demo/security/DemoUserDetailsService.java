package com.mg.demo.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * 시연용 계정 3개. 비밀번호는 모두 1234 ({noop}은 암호화하지 않는다는 뜻으로, 시연 전용).
 * user1(id 1), user2(id 2)는 일반 사용자, admin(id 99)은 관리자.
 */
public class DemoUserDetailsService implements UserDetailsService {

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return switch (username) {
            case "user1" -> new LoginUser(1L, "user1", "{noop}1234", "USER", "김민수");
            case "user2" -> new LoginUser(2L, "user2", "{noop}1234", "USER", "이서연");
            case "admin" -> new LoginUser(99L, "admin", "{noop}1234", "ADMIN", "관리자");
            default -> throw new UsernameNotFoundException(username);
        };
    }
}
