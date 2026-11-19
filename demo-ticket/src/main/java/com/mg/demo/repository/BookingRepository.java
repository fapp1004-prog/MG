package com.mg.demo.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import com.mg.demo.domain.Booking;

/** 시연용 메모리 저장소. 서버를 다시 켜면 처음 상태로 돌아간다. */
@Repository
public class BookingRepository {

    private final Map<Long, Booking> store = new ConcurrentHashMap<>();

    public BookingRepository() {
        store.put(1043L, new Booking(1043L, 1L, "user1", "김민수", "010-****-1234", "겨울 콘서트", "A-12", 110000, "예매완료"));
        store.put(1044L, new Booking(1044L, 2L, "user2", "이서연", "010-****-5521", "겨울 콘서트", "B-07", 132000, "예매완료"));
        store.put(1045L, new Booking(1045L, 2L, "user2", "이서연", "010-****-5521", "봄 페스티벌", "C-03", 88000, "예매완료"));
    }

    public Optional<Booking> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public Optional<Booking> findByIdAndUserId(Long id, Long userId) {
        return findById(id).filter(b -> b.getUserId().equals(userId));
    }

    public List<Booking> findByUserId(Long userId) {
        return store.values().stream()
                .filter(b -> b.getUserId().equals(userId))
                .sorted((a, b) -> a.getBookingId().compareTo(b.getBookingId()))
                .toList();
    }
}
