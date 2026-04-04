package yier.bubu.base.statemachine.order;

import org.junit.Assert;
import org.junit.Test;
import yier.bubu.base.statemachine.RejectionReason;
import yier.bubu.base.statemachine.StateMachine;
import yier.bubu.base.statemachine.TransitionResult;

import java.util.Arrays;

public class OrderStateMachineTest {

    @Test
    public void happyPath_shouldCompleteOrderAndRecordAuditLogs() {
        StateMachine<OrderState, OrderEvent, OrderContext> machine = OrderStateMachineFactory.create();
        OrderContext context = new OrderContext("ORDER-100");

        TransitionResult<OrderState, OrderEvent, OrderContext> payResult =
                machine.fire(OrderState.CREATED, OrderEvent.PAY, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> shipResult =
                machine.fire(payResult.getTargetState(), OrderEvent.SHIP, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> completeResult =
                machine.fire(shipResult.getTargetState(), OrderEvent.COMPLETE, context);

        Assert.assertTrue(payResult.isSuccess());
        Assert.assertEquals(OrderState.PAID, payResult.getTargetState());
        Assert.assertTrue(shipResult.isSuccess());
        Assert.assertEquals(OrderState.SHIPPED, shipResult.getTargetState());
        Assert.assertTrue(completeResult.isSuccess());
        Assert.assertEquals(OrderState.COMPLETED, completeResult.getTargetState());
        Assert.assertEquals(100L, context.getPaidAmount());
        Assert.assertTrue(context.hasShipment());
        Assert.assertEquals(
                Arrays.asList(
                        "payment captured",
                        "transition:CREATED->PAID by PAY",
                        "shipment dispatched",
                        "transition:PAID->SHIPPED by SHIP",
                        "transition:SHIPPED->COMPLETED by COMPLETE"),
                context.getAuditLogs());
    }

    @Test
    public void retryPaymentPath_shouldRecoverFromFailedPayment() {
        StateMachine<OrderState, OrderEvent, OrderContext> machine = OrderStateMachineFactory.create();
        OrderContext context = new OrderContext("ORDER-101");

        TransitionResult<OrderState, OrderEvent, OrderContext> failResult =
                machine.fire(OrderState.CREATED, OrderEvent.PAYMENT_FAIL, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> retryResult =
                machine.fire(failResult.getTargetState(), OrderEvent.PAY, context);

        Assert.assertTrue(failResult.isSuccess());
        Assert.assertEquals(OrderState.PAYMENT_FAILED, failResult.getTargetState());
        Assert.assertTrue(retryResult.isSuccess());
        Assert.assertEquals(OrderState.PAID, retryResult.getTargetState());
        Assert.assertEquals(100L, context.getPaidAmount());
        Assert.assertEquals(
                Arrays.asList(
                        "transition:CREATED->PAYMENT_FAILED by PAYMENT_FAIL",
                        "payment captured",
                        "transition:PAYMENT_FAILED->PAID by PAY"),
                context.getAuditLogs());
    }

    @Test
    public void cancellationPath_shouldCancelCreatedOrder() {
        StateMachine<OrderState, OrderEvent, OrderContext> machine = OrderStateMachineFactory.create();
        OrderContext context = new OrderContext("ORDER-102");

        TransitionResult<OrderState, OrderEvent, OrderContext> result =
                machine.fire(OrderState.CREATED, OrderEvent.CANCEL, context);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(OrderState.CANCELLED, result.getTargetState());
        Assert.assertEquals(Arrays.asList("transition:CREATED->CANCELLED by CANCEL"), context.getAuditLogs());
    }

    @Test
    public void cancellationPath_shouldCancelPaymentFailedOrder() {
        StateMachine<OrderState, OrderEvent, OrderContext> machine = OrderStateMachineFactory.create();
        OrderContext context = new OrderContext("ORDER-102A");

        TransitionResult<OrderState, OrderEvent, OrderContext> failResult =
                machine.fire(OrderState.CREATED, OrderEvent.PAYMENT_FAIL, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> cancelResult =
                machine.fire(failResult.getTargetState(), OrderEvent.CANCEL, context);

        Assert.assertTrue(failResult.isSuccess());
        Assert.assertEquals(OrderState.PAYMENT_FAILED, failResult.getTargetState());
        Assert.assertTrue(cancelResult.isSuccess());
        Assert.assertEquals(OrderState.CANCELLED, cancelResult.getTargetState());
        Assert.assertEquals(
                Arrays.asList(
                        "transition:CREATED->PAYMENT_FAILED by PAYMENT_FAIL",
                        "transition:PAYMENT_FAILED->CANCELLED by CANCEL"),
                context.getAuditLogs());
    }

    @Test
    public void refundPath_shouldMoveFromPaidToRefunded() {
        StateMachine<OrderState, OrderEvent, OrderContext> machine = OrderStateMachineFactory.create();
        OrderContext context = new OrderContext("ORDER-103");

        TransitionResult<OrderState, OrderEvent, OrderContext> payResult =
                machine.fire(OrderState.CREATED, OrderEvent.PAY, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> applyRefundResult =
                machine.fire(payResult.getTargetState(), OrderEvent.APPLY_REFUND, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> refundSuccessResult =
                machine.fire(applyRefundResult.getTargetState(), OrderEvent.REFUND_SUCCESS, context);

        Assert.assertTrue(payResult.isSuccess());
        Assert.assertTrue(applyRefundResult.isSuccess());
        Assert.assertEquals(OrderState.REFUNDING, applyRefundResult.getTargetState());
        Assert.assertTrue(refundSuccessResult.isSuccess());
        Assert.assertEquals(OrderState.REFUNDED, refundSuccessResult.getTargetState());
        Assert.assertTrue(context.isRefundRequested());
        Assert.assertEquals(
                Arrays.asList(
                        "payment captured",
                        "transition:CREATED->PAID by PAY",
                        "refund requested",
                        "transition:PAID->REFUNDING by APPLY_REFUND",
                        "transition:REFUNDING->REFUNDED by REFUND_SUCCESS"),
                context.getAuditLogs());
    }

    @Test
    public void refundPath_shouldApplyRefundFromCompletedOrder() {
        StateMachine<OrderState, OrderEvent, OrderContext> machine = OrderStateMachineFactory.create();
        OrderContext context = new OrderContext("ORDER-103A");

        TransitionResult<OrderState, OrderEvent, OrderContext> payResult =
                machine.fire(OrderState.CREATED, OrderEvent.PAY, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> shipResult =
                machine.fire(payResult.getTargetState(), OrderEvent.SHIP, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> completeResult =
                machine.fire(shipResult.getTargetState(), OrderEvent.COMPLETE, context);
        TransitionResult<OrderState, OrderEvent, OrderContext> refundResult =
                machine.fire(completeResult.getTargetState(), OrderEvent.APPLY_REFUND, context);

        Assert.assertTrue(payResult.isSuccess());
        Assert.assertTrue(shipResult.isSuccess());
        Assert.assertTrue(completeResult.isSuccess());
        Assert.assertEquals(OrderState.COMPLETED, completeResult.getTargetState());
        Assert.assertTrue(refundResult.isSuccess());
        Assert.assertEquals(OrderState.REFUNDING, refundResult.getTargetState());
        Assert.assertTrue(context.isRefundRequested());
        Assert.assertEquals(
                Arrays.asList(
                        "payment captured",
                        "transition:CREATED->PAID by PAY",
                        "shipment dispatched",
                        "transition:PAID->SHIPPED by SHIP",
                        "transition:SHIPPED->COMPLETED by COMPLETE",
                        "refund requested",
                        "transition:COMPLETED->REFUNDING by APPLY_REFUND"),
                context.getAuditLogs());
    }

    @Test
    public void illegalTransition_shouldBeRejectedWhenNoTransitionIsDefined() {
        StateMachine<OrderState, OrderEvent, OrderContext> machine = OrderStateMachineFactory.create();
        OrderContext context = new OrderContext("ORDER-104");

        TransitionResult<OrderState, OrderEvent, OrderContext> result =
                machine.fire(OrderState.CREATED, OrderEvent.SHIP, context);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(RejectionReason.NO_TRANSITION_DEFINED, result.getRejectionReason());
        Assert.assertNull(result.getTargetState());
        Assert.assertTrue(context.getAuditLogs().isEmpty());
    }

    @Test
    public void shippingShouldBeRejectedWhenPaidAmountIsZero() {
        StateMachine<OrderState, OrderEvent, OrderContext> machine = OrderStateMachineFactory.create();
        OrderContext context = new OrderContext("ORDER-105");

        TransitionResult<OrderState, OrderEvent, OrderContext> result =
                machine.fire(OrderState.PAID, OrderEvent.SHIP, context);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(RejectionReason.GUARD_REJECTED, result.getRejectionReason());
        Assert.assertNull(result.getTargetState());
        Assert.assertFalse(context.hasShipment());
        Assert.assertTrue(context.getAuditLogs().isEmpty());
    }

    @Test
    public void addAuditLog_shouldRejectNullInput() {
        OrderContext context = new OrderContext("ORDER-106");

        try {
            context.addAuditLog(null);
            Assert.fail("Expected null audit log to be rejected");
        } catch (NullPointerException exception) {
            Assert.assertEquals("auditLog", exception.getMessage());
        }
    }
}
