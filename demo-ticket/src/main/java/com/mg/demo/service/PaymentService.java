package com.mg.demo.service;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.mg.demo.domain.Payment;
import com.mg.demo.domain.Seat;

@Service
public class PaymentService {

    private final AtomicLong seq = new AtomicLong(5000);

    /** amount를 그대로 결제한다. 금액을 무엇으로 정할지는 호출하는 쪽(컨트롤러) 책임. */
    public Payment charge(Long userId, Seat seat, Long amount) {
        long paid = amount == null ? seat.getPrice() : amount;
        return new Payment(seq.incrementAndGet(), userId, seat.getSeatId(), seat.getPrice(), paid, "결제완료");
    }
}
