package com.mg.demo.domain;

/**
 * @param listPrice 서버에 저장된 정가
 * @param amount    실제로 결제된 금액
 */
public record Payment(Long paymentId, Long userId, String seatId, long listPrice, long amount, String result) {
}
