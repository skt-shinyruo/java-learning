package yier.bubu.base.statemachine.order;

import yier.bubu.base.statemachine.StateMachine;
import yier.bubu.base.statemachine.StateMachineBuilder;
import yier.bubu.base.statemachine.StateMachineListener;
import yier.bubu.base.statemachine.TransitionContext;

public final class OrderStateMachineFactory {
    private static final long DEMO_PAID_AMOUNT = 100L;

    private OrderStateMachineFactory() {
    }

    public static StateMachine<OrderState, OrderEvent, OrderContext> create() {
        return new StateMachineBuilder<OrderState, OrderEvent, OrderContext>()
                .addTransition(
                        OrderState.CREATED,
                        OrderEvent.PAY,
                        OrderState.PAID,
                        null,
                        OrderStateMachineFactory::capturePayment)
                .addTransition(OrderState.CREATED, OrderEvent.PAYMENT_FAIL, OrderState.PAYMENT_FAILED)
                .addTransition(OrderState.CREATED, OrderEvent.CANCEL, OrderState.CANCELLED)
                .addTransition(
                        OrderState.PAYMENT_FAILED,
                        OrderEvent.PAY,
                        OrderState.PAID,
                        null,
                        OrderStateMachineFactory::capturePayment)
                .addTransition(OrderState.PAYMENT_FAILED, OrderEvent.CANCEL, OrderState.CANCELLED)
                .addTransition(
                        OrderState.PAID,
                        OrderEvent.SHIP,
                        OrderState.SHIPPED,
                        transitionContext -> transitionContext.getContext().getPaidAmount() > 0L,
                        transitionContext -> {
                            transitionContext.getContext().markShipped();
                            transitionContext.getContext().addAuditLog("shipment dispatched");
                        })
                .addTransition(
                        OrderState.SHIPPED,
                        OrderEvent.COMPLETE,
                        OrderState.COMPLETED,
                        transitionContext -> transitionContext.getContext().hasShipment())
                .addTransition(
                        OrderState.PAID,
                        OrderEvent.APPLY_REFUND,
                        OrderState.REFUNDING,
                        null,
                        OrderStateMachineFactory::requestRefund)
                .addTransition(
                        OrderState.COMPLETED,
                        OrderEvent.APPLY_REFUND,
                        OrderState.REFUNDING,
                        null,
                        OrderStateMachineFactory::requestRefund)
                .addTransition(OrderState.REFUNDING, OrderEvent.REFUND_SUCCESS, OrderState.REFUNDED)
                .addListener(new StateMachineListener<OrderState, OrderEvent, OrderContext>() {
                    @Override
                    public void onSuccess(TransitionContext<OrderState, OrderEvent, OrderContext> transitionContext) {
                        transitionContext.getContext().addAuditLog(
                                "transition:" + transitionContext.getSourceState()
                                        + "->" + transitionContext.getTargetState()
                                        + " by " + transitionContext.getEvent());
                    }
                })
                .build();
    }

    private static void capturePayment(TransitionContext<OrderState, OrderEvent, OrderContext> transitionContext) {
        // 示例里用固定金额模拟支付入账，真实系统应由支付结果回填金额。
        transitionContext.getContext().markPaid(DEMO_PAID_AMOUNT);
        transitionContext.getContext().addAuditLog("payment captured");
    }

    private static void requestRefund(TransitionContext<OrderState, OrderEvent, OrderContext> transitionContext) {
        transitionContext.getContext().markRefundRequested();
        transitionContext.getContext().addAuditLog("refund requested");
    }
}
