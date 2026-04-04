package yier.bubu.base.statemachine.order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class OrderContext {
    private final String orderId;
    private long paidAmount;
    private boolean hasShipment;
    private boolean refundRequested;
    private final List<String> auditLogs = new ArrayList<String>();

    public OrderContext(String orderId) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
    }

    public String getOrderId() {
        return orderId;
    }

    public long getPaidAmount() {
        return paidAmount;
    }

    public boolean hasShipment() {
        return hasShipment;
    }

    public boolean isRefundRequested() {
        return refundRequested;
    }

    public void markPaid(long amount) {
        this.paidAmount = amount;
    }

    public void markShipped() {
        this.hasShipment = true;
    }

    public void markRefundRequested() {
        this.refundRequested = true;
    }

    public void addAuditLog(String auditLog) {
        auditLogs.add(auditLog);
    }

    public List<String> getAuditLogs() {
        return Collections.unmodifiableList(auditLogs);
    }
}
