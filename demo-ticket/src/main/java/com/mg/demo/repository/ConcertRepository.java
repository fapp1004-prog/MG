package com.mg.demo.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.mg.demo.domain.Concert;

@Repository
public class ConcertRepository {

    private final List<Concert> concerts = List.of(
            new Concert(1L, "겨울 콘서트", "2026-12-24", "올림픽홀"),
            new Concert(2L, "봄 페스티벌", "2027-04-10", "난지공원"));

    public List<Concert> findAll() {
        return concerts;
    }
}
