package com.mg.demo.domain;

/** 좌석. 가격은 서버에 저장된 값이 기준이다. */
public class Seat {

    private final String seatId;
    private final String concert;
    private final long price;
    private Long assignedUserId;

    public Seat(String seatId, String concert, long price) {
        this.seatId = seatId;
        this.concert = concert;
        this.price = price;
    }

    public String getSeatId() {
        return seatId;
    }

    public String getConcert() {
        return concert;
    }

    public long getPrice() {
        return price;
    }

    public Long getAssignedUserId() {
        return assignedUserId;
    }

    public void setAssignedUserId(Long assignedUserId) {
        this.assignedUserId = assignedUserId;
    }
}
