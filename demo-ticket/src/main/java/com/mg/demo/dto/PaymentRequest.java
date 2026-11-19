package com.mg.demo.dto;

/** 결제 요청. 화면은 price를 수정할 수 없게 막아 두었지만, 요청을 직접 만들면 아무 값이나 넣을 수 있다. */
public class PaymentRequest {

    private String seatId;
    private Long price;

    public String getSeatId() {
        return seatId;
    }

    public void setSeatId(String seatId) {
        this.seatId = seatId;
    }

    public Long getPrice() {
        return price;
    }

    public void setPrice(Long price) {
        this.price = price;
    }
}
