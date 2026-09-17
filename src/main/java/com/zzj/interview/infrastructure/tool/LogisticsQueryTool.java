package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 物流查询工具
 *
 * 当AI识别到用户想查询物流信息时，通过Function Calling调用此工具
 * 实际场景中会调用第三方物流API
 */
@Component
public class LogisticsQueryTool implements BusinessTool {

    @Override
    public String name() {
        return "query_logistics";
    }

    @Override
    public String description() {
        return "根据订单号查询物流信息，包括快递公司、运单号、物流轨迹等";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.requiredString("orderNo", "订单号，如10086")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String orderNo = (String) arguments.getOrDefault("orderNo", "UNKNOWN");

        // 模拟物流数据，实际场景中调用第三方物流API
        String logisticsInfo = String.format("""
                {
                    "orderNo": "%s",
                    "carrier": "顺丰速运",
                    "trackingNo": "SF1234567890",
                    "status": "运输中",
                    "estimatedDelivery": "2026-09-17",
                    "latestLocation": "上海转运中心",
                    "tracks": [
                        {"time": "2026-09-14 08:00", "location": "深圳发货仓", "action": "已揽收"},
                        {"time": "2026-09-14 22:00", "location": "深圳转运中心", "action": "已发出"},
                        {"time": "2026-09-15 10:00", "location": "上海转运中心", "action": "运输中"}
                    ]
                }""", orderNo);

        return ToolResult.success(name(), logisticsInfo);
    }
}
