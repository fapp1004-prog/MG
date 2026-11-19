package com.mg.demo.domain;

/** 예매 내역. 이름과 연락처는 개인정보라서 본인 외에는 보이면 안 된다. */
public class Booking {

    private final Long bookingId;
    private final Long userId;
    private final String owner;
    private final String name;
    private final String phone;
    private final String concert;
    private final String seat;
    private final long paid;
    private String status;

    public Booking(Long bookingId, Long userId, String owner, String name, String phone,
                   String concert, String seat, long paid, String status) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.owner = owner;
        this.name = name;
        this.phone = phone;
        this.concert = concert;
        this.seat = seat;
        this.paid = paid;
        this.status = status;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public String getConcert() {
        return concert;
    }

    public String getSeat() {
        return seat;
    }

    public long getPaid() {
        return paid;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
