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
 * 取消订单工具「后置校验」测试
 *
 * 核心验证点：cancel_order 不再只拼假字符串，而是
 * 1. 真正调用 UPDATE 落库；
 * 2. 更新后回查订单确认状态变为 CANCELLED（后置校验），校验不过则返回失败；
 * 3. 订单不存在 / 已取消 / 更新0行 等边界的诚实处理。
 *
 * OrderMapper 用 Mockito 打桩，无需真实数据库。
 */
@DisplayName("取消订单工具后置校验测试")
class CancelOrderToolTest {

    private OrderMapper orderMapper;
    private CancelOrderTool tool;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        tool = new CancelOrderTool(orderMapper);
    }

    private OrderEntity order(String status) {
        OrderEntity o = new OrderEntity();
        o.setId("ORD001");
        o.setOrderNo("10086");
        o.setCustomerId("C001");
        o.setStatus(status);
        o.setAmount(new BigDecimal("299.00"));
        return o;
    }

    private Map<String, Object> args() {
        Map<String, Object> a = new HashMap<>();
        a.put("orderNo", "10086");
        a.put("reason", "不想要了");
        return a;
    }

    @Test
    @DisplayName("取消成功 - 落库UPDATE且回查确认为已取消")
    void cancel_success_verifiesPostCondition() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("SHIPPED"), order("CANCELLED"));
        when(orderMapper.updateStatusByOrderNo("10086", "CANCELLED")).thenReturn(1);

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("已取消"), "回复应体现已取消");
        assertTrue(result.getData().contains("已发货"), "应带上取消前状态");
        assertTrue(result.getData().contains("299.00"), "退款金额应取订单真实金额");
        verify(orderMapper).updateStatusByOrderNo("10086", "CANCELLED");
        verify(orderMapper, times(2)).selectByOrderNo("10086"); // 取消前 + 后置校验各一次
    }

    @Test
    @DisplayName("后置校验失败 - 回查状态未变则返回失败，不谎报成功")
    void cancel_postConditionFails_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("SHIPPED"), order("SHIPPED"));
        when(orderMapper.updateStatusByOrderNo("10086", "CANCELLED")).thenReturn(1);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("校验未通过"));
    }

    @Test
    @DisplayName("订单不存在 - 返回失败且不写库")
    void cancel_orderNotFound_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(null);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未找到订单"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("订单已取消 - 幂等成功且不重复UPDATE")
    void cancel_alreadyCancelled_idempotent() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("CANCELLED"));

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("无需重复"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("已签收订单 - 不可取消，提示走售后，且不写库")
    void cancel_delivered_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("DELIVERED"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("已签收"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("退款中订单 - 不可取消，且不写库")
    void cancel_refunding_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("REFUNDING"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("退款中"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("已退款订单 - 不可取消，且不写库")
    void cancel_refunded_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("REFUNDED"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("已退款"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("UPDATE影响0行 - 返回失败且不再回查")
    void cancel_updateAffectsNoRow_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("SHIPPED"));
        when(orderMapper.updateStatusByOrderNo(eq("10086"), any())).thenReturn(0);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("状态未更新"));
        verify(orderMapper, times(1)).selectByOrderNo("10086");
    }
}
