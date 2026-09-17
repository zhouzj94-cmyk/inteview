package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.persistence.entity.AfterSaleEntity;
import com.zzj.interview.infrastructure.persistence.entity.OrderEntity;
import com.zzj.interview.infrastructure.persistence.mapper.AfterSaleMapper;
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
 * 退款到账处理工具测试
 *
 * 验证点：process_refund 为资金侧敏感操作（需确认）；仅"退款中"可推进到账，已退款幂等，
 * 其余状态拒绝；同步把订单与 after_sale 记录都置为 REFUNDED，并回查校验。
 * OrderMapper / AfterSaleMapper 用 Mockito 打桩。
 */
@DisplayName("退款到账处理工具测试")
class ProcessRefundToolTest {

    private OrderMapper orderMapper;
    private AfterSaleMapper afterSaleMapper;
    private ProcessRefundTool tool;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        afterSaleMapper = mock(AfterSaleMapper.class);
        tool = new ProcessRefundTool(orderMapper, afterSaleMapper);
    }

    private OrderEntity order(String status) {
        OrderEntity o = new OrderEntity();
        o.setId("ORD002");
        o.setOrderNo("10087");
        o.setCustomerId("C001");
        o.setStatus(status);
        o.setAmount(new BigDecimal("159.00"));
        return o;
    }

    private AfterSaleEntity afterSale(String status) {
        AfterSaleEntity a = new AfterSaleEntity();
        a.setId("ASR001");
        a.setAfterSaleNo("AS10087");
        a.setOrderNo("10087");
        a.setCustomerId("C001");
        a.setType("退款");
        a.setReason("商品质量问题");
        a.setStatus(status);
        a.setRefundAmount(new BigDecimal("159.00"));
        return a;
    }

    private Map<String, Object> args() {
        Map<String, Object> a = new HashMap<>();
        a.put("orderNo", "10087");
        return a;
    }

    @Test
    @DisplayName("敏感操作 - 退款到账需人工确认")
    void refund_requiresConfirmation() {
        assertTrue(tool.requiresConfirmation());
    }

    @Test
    @DisplayName("退款到账成功 - 订单与售后记录同步置REFUNDED并回查确认")
    void refund_success_updatesOrderAndAfterSale() {
        when(orderMapper.selectByOrderNo("10087")).thenReturn(order("REFUNDING"), order("REFUNDED"));
        when(orderMapper.updateStatusByOrderNo("10087", "REFUNDED")).thenReturn(1);
        when(afterSaleMapper.selectByOrderNo("10087")).thenReturn(afterSale("REFUNDING"));
        when(afterSaleMapper.updateStatusByOrderNo("10087", "REFUNDED")).thenReturn(1);

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("已退款"), "回复应体现已退款");
        assertTrue(result.getData().contains("退款中"), "应带上到账前状态");
        assertTrue(result.getData().contains("159.00"), "退款金额应取售后记录金额");
        assertTrue(result.getData().contains("AS10087"), "应带上售后单号");
        verify(orderMapper).updateStatusByOrderNo("10087", "REFUNDED");
        verify(afterSaleMapper).updateStatusByOrderNo("10087", "REFUNDED");
        verify(orderMapper, times(2)).selectByOrderNo("10087");
    }

    @Test
    @DisplayName("无售后记录 - 仍可到账，售后单号由订单号派生，不更新售后表")
    void refund_success_withoutAfterSaleRecord() {
        when(orderMapper.selectByOrderNo("10087")).thenReturn(order("REFUNDING"), order("REFUNDED"));
        when(orderMapper.updateStatusByOrderNo("10087", "REFUNDED")).thenReturn(1);
        when(afterSaleMapper.selectByOrderNo("10087")).thenReturn(null);

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("AS10087"), "售后单号应由订单号派生");
        assertTrue(result.getData().contains("159.00"), "无售后记录时退款金额回退订单金额");
        verify(afterSaleMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("后置校验失败 - 回查状态未变则返回失败")
    void refund_postConditionFails_returnsFailure() {
        when(orderMapper.selectByOrderNo("10087")).thenReturn(order("REFUNDING"), order("REFUNDING"));
        when(orderMapper.updateStatusByOrderNo("10087", "REFUNDED")).thenReturn(1);
        when(afterSaleMapper.selectByOrderNo("10087")).thenReturn(afterSale("REFUNDING"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("校验未通过"));
    }

    @Test
    @DisplayName("订单不存在 - 返回失败且不写库")
    void refund_orderNotFound_returnsFailure() {
        when(orderMapper.selectByOrderNo("10087")).thenReturn(null);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未找到订单"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("已退款 - 幂等成功且不重复UPDATE")
    void refund_alreadyRefunded_idempotent() {
        when(orderMapper.selectByOrderNo("10087")).thenReturn(order("REFUNDED"));

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("无需重复"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("非退款中状态 - 拒绝到账且不写库")
    void refund_notRefunding_returnsFailure() {
        when(orderMapper.selectByOrderNo("10087")).thenReturn(order("PAID"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("不在退款中"));
        verify(orderMapper, never()).updateStatusByOrderNo(any(), any());
    }

    @Test
    @DisplayName("UPDATE影响0行 - 返回失败且不再回查")
    void refund_updateAffectsNoRow_returnsFailure() {
        when(orderMapper.selectByOrderNo("10087")).thenReturn(order("REFUNDING"));
        when(orderMapper.updateStatusByOrderNo(eq("10087"), any())).thenReturn(0);
        when(afterSaleMapper.selectByOrderNo("10087")).thenReturn(afterSale("REFUNDING"));

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("状态未更新"));
        verify(orderMapper, times(1)).selectByOrderNo("10087");
    }
}
