package com.mg.demo.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.mg.demo.domain.Booking;
import com.mg.demo.repository.BookingRepository;
import com.mg.demo.security.LoginUser;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;

    public BookingService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    /**
     * 취약 버전 컨트롤러가 쓰는 메서드. 컨트롤러에는 검사가 없지만 여기서 소유자를 확인한다.
     * MG는 컨트롤러만 읽기 때문에 이 경우를 「불확실」로 표시하고, 개발자가 여기를 열어 「오탐」으로 처리하는 시나리오.
     */
    public Booking cancel(Long id, LoginUser user) {
        Booking booking = bookingRepository.findById(id).orElseThrow();
        if (!booking.getUserId().equals(user.getId())) {
            throw new AccessDeniedException("본인 예매만 취소할 수 있습니다");
        }
        booking.setStatus("취소됨");
        return booking;
    }

    /** 고친 버전 컨트롤러가 쓰는 메서드. 소유자 확인은 컨트롤러에서 끝냈다. */
    public Booking cancel(Booking booking) {
        booking.setStatus("취소됨");
        return booking;
    }
}
