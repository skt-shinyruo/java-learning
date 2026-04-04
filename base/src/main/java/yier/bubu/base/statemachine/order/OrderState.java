package yier.bubu.base.statemachine.order;

public enum OrderState {
    CREATED,
    PAID,
    PAYMENT_FAILED,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    REFUNDING,
    REFUNDED
}
