package com.zzj.interview.infrastructure.rag;

import com.zzj.interview.domain.rag.KnowledgeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存知识库单元测试
 *
 * 测试要点：
 * 1. FAQ种子数据加载
 * 2. 关键词匹配检索
 * 3. 中文分词匹配
 * 4. 相关性排序
 */
@DisplayName("内存知识库测试")
class InMemoryKnowledgeBaseTest {

    private InMemoryKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = new InMemoryKnowledgeBase();
        knowledgeBase.init();
    }

    @Test
    @DisplayName("初始化后应加载FAQ种子数据")
    void init_loadsFaqData() {
        List<KnowledgeChunk> results = knowledgeBase.retrieve("退货", 10);
        assertFalse(results.isEmpty(), "应能检索到退换货相关的知识");
    }

    @Test
    @DisplayName("检索退换货政策")
    void retrieve_returnPolicy() {
        List<KnowledgeChunk> results = knowledgeBase.retrieve("退换货政策", 3);

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).content().contains("退货") ||
                   results.get(0).content().contains("换货"));
    }

    @Test
    @DisplayName("检索物流时效")
    void retrieve_logisticsTime() {
        List<KnowledgeChunk> results = knowledgeBase.retrieve("物流几天到", 3);

        assertFalse(results.isEmpty());
        boolean hasLogistics = results.stream()
                .anyMatch(c -> c.content().contains("物流") || c.content().contains("发货"));
        assertTrue(hasLogistics, "应包含物流相关信息");
    }

    @Test
    @DisplayName("检索支付方式")
    void retrieve_paymentMethods() {
        List<KnowledgeChunk> results = knowledgeBase.retrieve("支付宝 微信", 3);

        assertFalse(results.isEmpty());
        boolean hasPayment = results.stream()
                .anyMatch(c -> c.content().contains("支付") || c.content().contains("支付宝"));
        assertTrue(hasPayment, "应包含支付相关信息");
    }

    @Test
    @DisplayName("空查询应返回空列表")
    void retrieve_emptyQuery_returnsEmpty() {
        List<KnowledgeChunk> results = knowledgeBase.retrieve("", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("null查询应返回空列表")
    void retrieve_nullQuery_returnsEmpty() {
        List<KnowledgeChunk> results = knowledgeBase.retrieve(null, 5);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("topK限制应生效")
    void retrieve_topKLimit() {
        List<KnowledgeChunk> results = knowledgeBase.retrieve("订单 物流 支付 售后", 2);
        assertTrue(results.size() <= 2, "返回结果不应超过topK");
    }

    @Test
    @DisplayName("动态添加知识片段后可检索")
    void addChunk_thenRetrieve() {
        KnowledgeChunk custom = new KnowledgeChunk(
                "custom-1",
                "测试知识片段：VIP客户享专属折扣",
                "test",
                List.of("VIP", "折扣", "客户")
        );
        knowledgeBase.addChunk(custom);

        List<KnowledgeChunk> results = knowledgeBase.retrieve("VIP折扣", 5);
        boolean found = results.stream().anyMatch(c -> c.id().equals("custom-1"));
        assertTrue(found, "应能检索到动态添加的知识");
    }

    @Test
    @DisplayName("相关性排序 - 关键词匹配分数更高")
    void retrieve_relevanceRanking() {
        List<KnowledgeChunk> results = knowledgeBase.retrieve("会员权益 积分", 5);

        assertFalse(results.isEmpty());
        if (results.size() > 1) {
            assertTrue(results.get(0).content().contains("会员") ||
                       results.get(0).content().contains("积分"),
                    "最相关的结果应排在前面");
        }
    }
}
