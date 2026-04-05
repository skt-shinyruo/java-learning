package yier.bubu.base.statemachine.order;

import org.junit.Assert;
import org.junit.Test;
import yier.bubu.base.statemachine.StateMachineInstance;
import yier.bubu.base.statemachine.TransitionResult;

import java.util.Arrays;

public class OrderStateMachineInstanceTest {
    @Test
    public void fire_shouldAdvanceOrderStateWithoutPassingCurrentStateManually() {
        OrderContext context = new OrderContext("ORDER-200");
        StateMachineInstance<OrderState, OrderEvent, OrderContext> instance =
                new StateMachineInstance<OrderState, OrderEvent, OrderContext>(
                        OrderStateMachineFactory.create(),
                        OrderState.CREATED,
                        context);

        TransitionResult<OrderState, OrderEvent, OrderContext> payResult = instance.fire(OrderEvent.PAY);
        Assert.assertTrue(payResult.isSuccess());
        Assert.assertEquals(OrderState.PAID, instance.getCurrentState());

        TransitionResult<OrderState, OrderEvent, OrderContext> shipResult = instance.fire(OrderEvent.SHIP);
        Assert.assertTrue(shipResult.isSuccess());
        Assert.assertEquals(OrderState.SHIPPED, instance.getCurrentState());

        TransitionResult<OrderState, OrderEvent, OrderContext> completeResult = instance.fire(OrderEvent.COMPLETE);
        Assert.assertTrue(completeResult.isSuccess());
        Assert.assertEquals(OrderState.COMPLETED, instance.getCurrentState());

        Assert.assertSame(context, instance.getContext());
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
}
