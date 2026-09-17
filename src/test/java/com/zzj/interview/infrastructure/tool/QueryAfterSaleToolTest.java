package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.persistence.entity.AfterSaleEntity;
import com.zzj.interview.infrastructure.persistence.mapper.AfterSaleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 售后进度查询工具测试
 *
 * 验证点：query_after_sale 为只读工具（无需确认）；命中时返回售后单号/类型/状态/退款金额，
 * 状态码经 OrderStatus 翻译为中文标签；未命中返回失败。AfterSaleMapper 用 Mockito 打桩。
 */
@DisplayName("售后进度查询工具测试")
class QueryAfterSaleToolTest {

    private AfterSaleMapper afterSaleMapper;
    private QueryAfterSaleTool tool;

    @BeforeEach
    void setUp() {
        afterSaleMapper = mock(AfterSaleMapper.class);
        tool = new QueryAfterSaleTool(afterSaleMapper);
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
        a.setCreatedAt(LocalDateTime.of(2026, 9, 16, 14, 0, 0));
        a.setUpdatedAt(LocalDateTime.of(2026, 9, 16, 14, 0, 0));
        return a;
    }

    private Map<String, Object> args() {
        Map<String, Object> a = new HashMap<>();
        a.put("orderNo", "10087");
        return a;
    }

    @Test
    @DisplayName("只读工具 - 无需人工确认")
    void query_notRequireConfirmation() {
        assertFalse(tool.requiresConfirmation());
    }

    @Test
    @DisplayName("查询成功 - 返回售后进度且状态翻译为中文标签")
    void query_success_returnsProgress() {
        when(afterSaleMapper.selectByOrderNo("10087")).thenReturn(afterSale("REFUNDING"));

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("AS10087"), "应带上售后单号");
        assertTrue(result.getData().contains("退款中"), "状态应翻译为中文标签");
        assertTrue(result.getData().contains("REFUNDING"), "应保留原始状态码");
        assertTrue(result.getData().contains("159.00"), "应带上退款金额");
        assertTrue(result.getData().contains("商品质量问题"), "应带上售后原因");
        verify(afterSaleMapper).selectByOrderNo("10087");
    }

    @Test
    @DisplayName("已退款记录 - 状态标签为已退款")
    void query_refunded_returnsLabel() {
        when(afterSaleMapper.selectByOrderNo("10087")).thenReturn(afterSale("REFUNDED"));

        ToolResult result = tool.execute(args());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("已退款"));
    }

    @Test
    @DisplayName("无售后记录 - 返回失败")
    void query_notFound_returnsFailure() {
        when(afterSaleMapper.selectByOrderNo("10087")).thenReturn(null);

        ToolResult result = tool.execute(args());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未找到订单的售后记录"));
    }
}
