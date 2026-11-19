package com.mg.demo.support;

import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 오류를 화면에서 읽기 쉬운 JSON으로 돌려준다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", 404, "error", "대상을 찾을 수 없습니다"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException e) {
        String msg = e.getMessage() == null || e.getMessage().startsWith("Access Denied") ? "권한이 없습니다" : e.getMessage();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", 403, "error", msg));
    }
}
