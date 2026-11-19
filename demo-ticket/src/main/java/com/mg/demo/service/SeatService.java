package com.mg.demo.service;

import org.springframework.stereotype.Service;

import com.mg.demo.domain.Seat;
import com.mg.demo.repository.SeatRepository;

@Service
public class SeatService {

    private final SeatRepository seatRepository;

    public SeatService(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    public Seat assign(String seatId, Long userId) {
        Seat seat = seatRepository.findById(seatId).orElseThrow();
        seat.setAssignedUserId(userId);
        return seat;
    }
}
