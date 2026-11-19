package com.mg.demo.dto;

/** 관리자용 좌석 수동 배정 요청. */
public class AssignRequest {

    private String seatId;
    private Long userId;

    public String getSeatId() {
        return seatId;
    }

    public void setSeatId(String seatId) {
        this.seatId = seatId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
