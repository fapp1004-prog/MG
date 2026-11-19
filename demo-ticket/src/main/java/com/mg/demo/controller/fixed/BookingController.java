package com.mg.demo.controller.fixed;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mg.demo.domain.Booking;
import com.mg.demo.domain.Concert;
import com.mg.demo.domain.Payment;
import com.mg.demo.domain.Seat;
import com.mg.demo.dto.AssignRequest;
import com.mg.demo.dto.PaymentRequest;
import com.mg.demo.repository.BookingRepository;
import com.mg.demo.repository.ConcertRepository;
import com.mg.demo.repository.SeatRepository;
import com.mg.demo.security.LoginUser;
import com.mg.demo.service.BookingService;
import com.mg.demo.service.PaymentService;
import com.mg.demo.service.SeatService;

/**
 * [고친 버전] --spring.profiles.active=fixed 로 실행할 때 활성. MG가 찾은 누락을 고쳤다.
 */
@RestController
@RequestMapping("/api")
@Profile("fixed")
public class BookingController {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final ConcertRepository concertRepository;
    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final SeatService seatService;

    public BookingController(BookingRepository bookingRepository, SeatRepository seatRepository,
                             ConcertRepository concertRepository, PaymentService paymentService,
                             BookingService bookingService, SeatService seatService) {
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.concertRepository = concertRepository;
        this.paymentService = paymentService;
        this.bookingService = bookingService;
        this.seatService = seatService;
    }

    // 공연 목록 (누구나 조회)
    @GetMapping("/concerts")
    public List<Concert> concerts() {
        return concertRepository.findAll();
    }

    // 예매 내역 상세: 소유자와 로그인 사용자를 비교
    @GetMapping("/bookings/{id}")
    public Booking getBooking(@PathVariable Long id,
                              @AuthenticationPrincipal LoginUser user) {
        Booking booking = bookingRepository.findById(id).orElseThrow();
        if (!booking.getUserId().equals(user.getId())) {
            throw new AccessDeniedException("본인 예매만 조회할 수 있습니다");
        }
        return booking;
    }

    // 내 예매 목록
    @GetMapping("/bookings")
    public List<Booking> myBookings(@AuthenticationPrincipal LoginUser user) {
        return bookingRepository.findByUserId(user.getId());
    }

    // 예매 취소: 컨트롤러에서 소유자 확인
    @PostMapping("/bookings/{id}/cancel")
    public Booking cancel(@PathVariable Long id,
                          @AuthenticationPrincipal LoginUser user) {
        Booking booking = bookingRepository.findById(id).orElseThrow();
        if (!booking.getUserId().equals(user.getId())) {
            throw new AccessDeniedException("본인 예매만 취소할 수 있습니다");
        }
        return bookingService.cancel(booking);
    }

    // 결제: 금액은 서버에 저장된 좌석 가격
    @PostMapping("/payments")
    public Payment pay(@RequestBody PaymentRequest req,
                       @AuthenticationPrincipal LoginUser user) {
        Seat seat = seatRepository.findById(req.getSeatId()).orElseThrow();
        return paymentService.charge(user.getId(), seat, seat.getPrice());
    }

    // 좌석 수동 배정 (관리자용): ADMIN만
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/admin/seats/assign")
    public Seat assignSeat(@RequestBody AssignRequest req) {
        return seatService.assign(req.getSeatId(), req.getUserId());
    }
}
