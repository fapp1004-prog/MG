package com.example.ticket.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * [판단력 시험용 샘플] 실행하지 않는 읽기 전용 코드.
 * 다섯 정책을 모두 지키지만, 변수명과 검사 방식이 demo-ticket과 다르다.
 *  - 소유자 조건을 쿼리 메서드 이름에 넣음 (findOneByIdAndOwner_MemberNo)
 *  - 로그인 사용자 타입이 LoginMember, 식별자는 getMemberNo()
 *  - 금액은 별도 계산기(priceCalculator)로 정가를 구함
 *  - 관리자 확인을 어노테이션이 아니라 등급(enum) 비교로 함
 * 실제 LLM이라면 모두 「검사 있음」으로 판단해야 하고, 키워드로 흉내 내는 mock은 여기서 틀린다.
 */
@RestController
@RequestMapping("/api")
public class StyledBookingController {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final PriceCalculator priceCalculator;
    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final SeatService seatService;

    public StyledBookingController(BookingRepository bookingRepository, SeatRepository seatRepository,
                                   PriceCalculator priceCalculator, PaymentService paymentService,
                                   BookingService bookingService, SeatService seatService) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.priceCalculator = priceCalculator;
        this.paymentService = paymentService;
        this.bookingService = bookingService;
        this.seatService = seatService;
    }

    // 예매 내역 상세: 소유자 조건을 조회 쿼리에 포함
    @GetMapping("/bookings/{bookingId}")
    public Booking detail(@PathVariable Long bookingId,
                          @AuthenticationPrincipal LoginMember me) {
        return bookingRepository.findOneByIdAndOwner_MemberNo(bookingId, me.getMemberNo())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // 내 예매 목록
    @GetMapping("/bookings")
    public List<Booking> mine(@AuthenticationPrincipal LoginMember me) {
        return bookingRepository.findAllByOwner_MemberNo(me.getMemberNo());
    }

    // 예매 취소
    @PostMapping("/bookings/{bookingId}/cancel")
    public Booking cancel(@PathVariable Long bookingId,
                          @AuthenticationPrincipal LoginMember me) {
        Booking b = bookingRepository.findById(bookingId).orElseThrow();
        if (!b.getOwner().getMemberNo().equals(me.getMemberNo())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return bookingService.cancel(b);
    }

    // 결제: 금액은 좌석 정가로 계산
    @PostMapping("/payments")
    public Payment pay(@RequestBody PaymentRequest req,
                       @AuthenticationPrincipal LoginMember me) {
        Seat seat = seatRepository.findById(req.getSeatId()).orElseThrow();
        long amount = priceCalculator.listPriceOf(seat);
        return paymentService.charge(me.getMemberNo(), seat, amount);
    }

    // 좌석 수동 배정 (관리자용): 등급 비교
    @PostMapping("/admin/seats/assign")
    public Seat assign(@RequestBody AssignRequest req,
                       @AuthenticationPrincipal LoginMember me) {
        if (me.getGrade() != MemberGrade.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return seatService.assign(req.getSeatId(), req.getUserId());
    }
}
