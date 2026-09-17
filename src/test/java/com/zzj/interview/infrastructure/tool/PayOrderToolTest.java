package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.persistence.entity.OrderEntity;
import com.zzj.interview.infrastructure.persistence.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 订单支付工具测试
 *
 * 验证点：pay_order 为资金侧敏感操作（需确认）；仅待付款可支付，已支付/已发货/已签收幂等，
 * 已取消/退款中/已退款不可支付；真正 UPDATE 到 PAID 并回查校验。
 * OrderMapper 用 Mockito 打桩，无需真实数据库。
 */
@DisplayName("订单支付工具测试")
class PayOrderToolTest {

    private OrderMapper orderMapper;
    private PayOrderTool tool;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        tool = new PayOrderTool(orderMapper);
    }

    private OrderEntity order(String status) {
        OrderEntity o = new OrderEntity();
        o.setId("ORD016");
        o.setOrderNo("10101");
        o.setCustomerId("C001");
        o.setStatus(status);
        o.setAmount(new BigDecimal("199.00"));
        return o;
    }

    private Map<String, Object> args() {
        Map<String, Object> a = new HashMap<>();
        a.put("orderNo", "10101");
        a.put("payMethod", "支付宝");
        return a;
    }

    @Test
    @DisplayName("敏感操作 - 支付需人工确认")
    void pay_requiresConfirmation() {
        assertTrue(tool.requiresConfirmation());
    }

    @Test
    @DisplayName("支付成功 - 待付款落库PAID且回查确认")
    void pay_success_verifiesPostCondition() {
        when(orderMapper.selectByOrderNo("10101")).thenReturn(order("CREATED"), order("PAID"));
        when(orderMapper.updateStatusByOrderNo("10101", "PAID")).thenReturn(1);

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("已支付"), "回复应体现已支付");
        assertTrue(result.getData().contains("待付款"), "应带上支付前状态");
        assertTrue(result.getData().contains("199.00"), "应带上订单金额");
        assertTrue(result.getData().contains("支付宝"), "应带上支付方式");
        verify(orderMapper).updateStatusByOrderNo("10101", "PAID");
        verify(orderMapper, times(2)).selectByOrderNo("10101");
    }

    @Test
    @DisplayName("后置校验失败 - 回查状态未变则返回失败")
    void pay_postConditionFails_returnsFailure() {
        when(orderMapper.selectByOrderNo("10101")).thenReturn(order("CREATED"), order("CREATED"));
        when(orderMapper.updateStatusByOrderNo("10101", "PAID")).thenReturn(1);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("校验未通过"));
    }

    @Test
    @DisplayName("订单不存在 - 返回失败且不写库")
    void pay_orderNotFound_returnsFailure() {
        when(orderMapper.selectByOrderNo("10101")).thenReturn(null);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未找到订单"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("已支付 - 幂等成功且不重复UPDATE")
    void pay_alreadyPaid_idempotent() {
        when(orderMapper.selectByOrderNo("10101")).thenReturn(order("PAID"));

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("无需重复"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("已发货 - 视为已支付，幂等成功")
    void pay_alreadyShipped_idempotent() {
        when(orderMapper.selectByOrderNo("10101")).thenReturn(order("SHIPPED"));

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("无需重复"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("已取消订单 - 无法支付，返回失败且不写库")
    void pay_cancelled_returnsFailure() {
        when(orderMapper.selectByOrderNo("10101")).thenReturn(order("CANCELLED"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("已取消"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("退款中订单 - 无法支付，返回失败且不写库")
    void pay_refunding_returnsFailure() {
        when(orderMapper.selectByOrderNo("10101")).thenReturn(order("REFUNDING"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("退款"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("UPDATE影响0行 - 返回失败且不再回查")
    void pay_updateAffectsNoRow_returnsFailure() {
        when(orderMapper.selectByOrderNo("10101")).thenReturn(order("CREATED"));
        when(orderMapper.updateStatusByOrderNo(eq("10101"), any())).thenReturn(0);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("状态未更新"));
        verify(orderMapper, times(1)).selectByOrderNo("10101");
    }
}
