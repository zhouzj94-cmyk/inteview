package com.zzj.interview.infrastructure.rag;

import com.zzj.interview.domain.rag.KnowledgeBase;
import com.zzj.interview.domain.rag.KnowledgeChunk;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 内存知识库实现
 *
 * 简化版RAG：基于关键词匹配的检索
 * 生产环境可替换为向量数据库（如Milvus、Pinecone）
 *
 * 设计说明：
 * - 使用TF-IDF思想计算相关性
 * - 支持中文分词（简单字符匹配）
 * - 预加载FAQ和常见问题
 */
@Component
public class InMemoryKnowledgeBase implements KnowledgeBase {

    private final List<KnowledgeChunk> chunks = new ArrayList<>();

    @PostConstruct
    public void init() {
        // 预加载FAQ知识
        loadFaqKnowledge();
    }

    @Override
    public List<KnowledgeChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // 计算每个知识片段与查询的相关性分数
        return chunks.stream()
                .map(chunk -> Map.entry(chunk, calculateRelevance(query, chunk)))
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    @Override
    public void addChunk(KnowledgeChunk chunk) {
        chunks.add(chunk);
    }

    /**
     * 计算查询与知识片段的相关性
     * 简化版：关键词匹配 + 类别权重
     */
    private double calculateRelevance(String query, KnowledgeChunk chunk) {
        double score = 0;
        String queryLower = query.toLowerCase();
        String contentLower = chunk.content().toLowerCase();

        // 内容匹配
        for (String keyword : chunk.keywords()) {
            if (queryLower.contains(keyword.toLowerCase())) {
                score += 2.0;
            }
        }

        // 直接文本匹配
        String[] queryWords = queryLower.split("\\s+");
        for (String word : queryWords) {
            if (word.length() > 1 && contentLower.contains(word)) {
                score += 1.0;
            }
        }

        // 中文字符匹配（2字及以上）
        for (int i = 0; i < queryLower.length() - 1; i++) {
            String biGram = queryLower.substring(i, i + 2);
            if (contentLower.contains(biGram)) {
                score += 0.5;
            }
        }

        return score;
    }

    private void loadFaqKnowledge() {
        // 退换货政策
        addChunk(new KnowledgeChunk(
                "faq-return-1",
                "退换货政策：商品签收后7天内可申请无理由退货，15天内可申请换货。退货商品需保持原包装完好，附件齐全。退款将在收到退货商品后3-5个工作日内原路返回。",
                "policy",
                List.of("退货", "换货", "退款", "退换", "政策", "7天", "15天")
        ));

        // 物流时效
        addChunk(new KnowledgeChunk(
                "faq-logistics-1",
                "物流时效：普通订单付款后24小时内发货，偏远地区48小时内发货。顺丰快递一般1-3天到达，普通快递3-7天到达。节假日可能延迟。",
                "logistics",
                List.of("物流", "发货", "快递", "时效", "几天", "到达", "顺丰")
        ));

        // 支付方式
        addChunk(new KnowledgeChunk(
                "faq-payment-1",
                "支持的支付方式：支付宝、微信支付、银联卡、花呗（支持分期）。企业客户还支持对公转账和月结服务。",
                "payment",
                List.of("支付", "付款", "支付宝", "微信", "花呗", "分期")
        ));

        // 售后服务
        addChunk(new KnowledgeChunk(
                "faq-aftersale-1",
                "售后服务流程：1.提交售后申请 2.客服审核（24小时内）3.寄回商品 4.收到后检测 5.处理退款/换货 6.完成。全程可在订单详情查看进度。",
                "aftersale",
                List.of("售后", "流程", "申请", "审核", "进度")
        ));

        // 订单修改
        addChunk(new KnowledgeChunk(
                "faq-order-1",
                "订单修改：未发货订单可修改收货地址和联系方式。已发货订单无法修改，如需更改地址请联系快递客服。订单取消需在发货前申请。",
                "order",
                List.of("订单", "修改", "地址", "取消", "发货")
        ));

        // 发票相关
        addChunk(new KnowledgeChunk(
                "faq-invoice-1",
                "发票说明：支持电子发票和纸质发票。电子发票在订单完成后自动发送到您的邮箱。纸质发票需单独申请，7个工作日内寄出。发票抬头可在个人中心修改。",
                "invoice",
                List.of("发票", "电子", "纸质", "抬头", "邮箱")
        ));

        // 会员权益
        addChunk(new KnowledgeChunk(
                "faq-member-1",
                "会员权益：银卡会员享95折，金卡会员享9折免运费，钻石会员享85折+专属客服+优先发货。积分可兑换优惠券或礼品。会员等级每年1月1日重新计算。",
                "member",
                List.of("会员", "权益", "折扣", "积分", "银卡", "金卡", "钻石")
        ));
    }
}
