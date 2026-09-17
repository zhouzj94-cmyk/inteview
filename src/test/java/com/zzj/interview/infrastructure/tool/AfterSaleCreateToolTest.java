package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.persistence.entity.AfterSaleEntity;
import com.zzj.interview.infrastructure.persistence.entity.OrderEntity;
import com.zzj.interview.infrastructure.persistence.mapper.AfterSaleMapper;
import com.zzj.interview.infrastructure.persistence.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 售后工单工具「做实 + 后置校验」测试
 *
 * 核心验证点：create_after_sale 不再是硬编码空壳，而是
 * 1. 读真实订单、退款金额取订单真实 amount；
 * 2. 状态守卫：待付款/已取消不可退款，已退款/退款中幂等；
 * 3. 真正调用 UPDATE 把状态推进到 REFUNDING，并回查校验；
 * 4. 退款为资金侧敏感操作，requiresConfirmation=true。
 *
 * OrderMapper 用 Mockito 打桩，无需真实数据库。
 */
@DisplayName("售后工单工具做实与后置校验测试")
class AfterSaleCreateToolTest {

    private OrderMapper orderMapper;
    private AfterSaleMapper afterSaleMapper;
    private AfterSaleCreateTool tool;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        afterSaleMapper = mock(AfterSaleMapper.class);
        tool = new AfterSaleCreateTool(orderMapper, afterSaleMapper);
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
        a.put("reason", "商品质量问题");
        return a;
    }

    @Test
    @DisplayName("敏感操作 - 退款需人工确认")
    void afterSale_requiresConfirmation() {
        assertTrue(tool.requiresConfirmation());
    }

    @Test
    @DisplayName("售后成功 - 落库REFUNDING且回查确认，退款金额取订单真实金额，并写入售后记录")
    void afterSale_success_verifiesPostCondition() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("SHIPPED"), order("REFUNDING"));
        when(orderMapper.updateStatusByOrderNo("10086", "REFUNDING")).thenReturn(1);
        when(afterSaleMapper.insert(any())).thenReturn(1);

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("退款中"), "回复应体现退款中");
        assertTrue(result.getData().contains("已发货"), "应带上售后前状态");
        assertTrue(result.getData().contains("299.00"), "退款金额应取订单真实金额");
        assertTrue(result.getData().contains("AS10086"), "售后单号应由订单号派生");
        verify(orderMapper).updateStatusByOrderNo("10086", "REFUNDING");
        verify(orderMapper, times(2)).selectByOrderNo("10086"); // 申请前 + 后置校验各一次

        ArgumentCaptor<AfterSaleEntity> captor = ArgumentCaptor.forClass(AfterSaleEntity.class);
        verify(afterSaleMapper).insert(captor.capture());
        AfterSaleEntity saved = captor.getValue();
        assertEquals("AS10086", saved.getAfterSaleNo());
        assertEquals("10086", saved.getOrderNo());
        assertEquals("C001", saved.getCustomerId());
        assertEquals("REFUNDING", saved.getStatus());
        assertEquals("退款", saved.getType());
        assertEquals(new BigDecimal("299.00"), saved.getRefundAmount());
    }

    @Test
    @DisplayName("售后记录写入失败 - insert影响0行则返回失败")
    void afterSale_insertFails_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("SHIPPED"), order("REFUNDING"));
        when(orderMapper.updateStatusByOrderNo("10086", "REFUNDING")).thenReturn(1);
        when(afterSaleMapper.insert(any())).thenReturn(0);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("售后记录写入失败"));
    }

    @Test
    @DisplayName("后置校验失败 - 回查状态未变则返回失败，不谎报成功")
    void afterSale_postConditionFails_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("PAID"), order("PAID"));
        when(orderMapper.updateStatusByOrderNo("10086", "REFUNDING")).thenReturn(1);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("校验未通过"));
    }

    @Test
    @DisplayName("订单不存在 - 返回失败且不写库")
    void afterSale_orderNotFound_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(null);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未找到订单"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("已退款 - 幂等成功且不重复UPDATE")
    void afterSale_alreadyRefunded_idempotent() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("REFUNDED"));

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("无需重复"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("退款中 - 幂等成功且不重复UPDATE")
    void afterSale_alreadyRefunding_idempotent() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("REFUNDING"));

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("无需重复"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("待付款订单 - 无款可退，返回失败且不写库")
    void afterSale_unpaid_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("CREATED"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("尚未支付"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("已取消订单 - 走取消退款，返回失败且不写库")
    void afterSale_cancelled_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("CANCELLED"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("已取消"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("UPDATE影响0行 - 返回失败且不再回查")
    void afterSale_updateAffectsNoRow_returnsFailure() {
        when(orderMapper.selectByOrderNo("10086")).thenReturn(order("DELIVERED"));
        when(orderMapper.updateStatusByOrderNo(eq("10086"), any())).thenReturn(0);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("状态未更新"));
        verify(orderMapper, times(1)).selectByOrderNo("10086");
    }
}
