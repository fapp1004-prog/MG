package com.mg.demo.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.mg.demo.domain.Seat;

@Repository
public class SeatRepository {

    private final Map<String, Seat> store = new ConcurrentHashMap<>();

    public SeatRepository() {
        store.put("A-15", new Seat("A-15", "겨울 콘서트", 110000));
        store.put("B-01", new Seat("B-01", "겨울 콘서트", 132000));
        store.put("C-08", new Seat("C-08", "봄 페스티벌", 88000));
    }

    public Optional<Seat> findById(String seatId) {
        return Optional.ofNullable(store.get(seatId));
    }

    public List<Seat> findAll() {
        return store.values().stream().sorted((a, b) -> a.getSeatId().compareTo(b.getSeatId())).toList();
    }
}
