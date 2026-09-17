package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 结果验证上下文构造器
 *
 * 把工具执行结果转成回复上下文。关键原则：失败结果不能被静默丢弃。
 * - 成功 → 纳入其数据
 * - 失败 → 纳入显式的失败说明
 *
 * 让回复生成基于真实结果作答，避免在工具失败时凭空宣称成功。
 * 静态工具类，便于脱离图/处理器单测。
 */
public final class VerifiedContextBuilder {

    private VerifiedContextBuilder() {
    }

    public static List<String> build(List<ToolResult> toolResults) {
        List<String> context = new ArrayList<>();
        if (toolResults == null) {
            return context;
        }
        for (ToolResult tr : toolResults) {
            if (tr == null) {
                continue;
            }
            if (tr.isSuccess()) {
                if (tr.getData() != null) {
                    context.add(tr.getData());
                }
            } else {
                context.add(String.format("[工具 %s 执行失败] %s", tr.getToolName(),
                        tr.getErrorMessage() != null ? tr.getErrorMessage() : "未知错误"));
            }
        }
        return context;
    }
}
