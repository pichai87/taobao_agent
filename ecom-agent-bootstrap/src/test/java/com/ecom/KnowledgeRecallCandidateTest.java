package com.ecom;

import com.ecom.domain.Analysis.Evidence;
import com.ecom.domain.Ports.Knowledge;
import com.ecom.domain.Semantic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KnowledgeRecallCandidateTest {
    private static final String METRIC = "GMV";
    private static final String QUESTION = "GMV 口径 归因 血缘 SQL UV 订单 转化 模型";
    private static final String MODEL_ID = "r21-z-model-target";
    private static final String RULE_ID = "r21-z-rule-target";

    @Autowired JdbcTemplate db;
    @Autowired Knowledge knowledge;
    @Autowired Semantic.Workbench workbench;

    @Test
    void highlyRelevantPublishedDocumentsRemainRetrievableBeyondInitialCandidateLimits() {
        seedUnrelatedPublishedCandidates();
        insert(MODEL_ID, "L1_MODEL", "GMV 模型 血缘 SQL", "GMV 口径 归因 血缘 SQL UV 订单 转化 模型的高相关说明。 ");
        insert(RULE_ID, "L3_RULE", "GMV 口径 归因规则", "GMV 口径 归因 血缘 SQL UV 订单 转化 模型的高相关规则。 ");

        List<String> ruleIds = knowledge.recall(QUESTION, METRIC).stream().map(Evidence::id).toList();
        List<String> contextIds = knowledge.context(QUESTION, METRIC).stream().map(Evidence::id).toList();
        List<String> workbenchIds = workbench.recall(METRIC, QUESTION).stream().map(Semantic.KnowledgeHit::id).toList();

        assertSoftly(softly -> {
            softly.assertThat(ruleIds).as("主链规则召回应命中排在候选窗口外的高相关规则").contains(RULE_ID);
            softly.assertThat(contextIds).as("主链上下文应包含高相关规则与模型文档").contains(RULE_ID, MODEL_ID);
            softly.assertThat(workbenchIds).as("工作台召回应包含高相关规则与模型文档").contains(RULE_ID, MODEL_ID);
        });
    }

    @Test
    void relevantKnowledgeIsFoundWhenQuestionHasNoHardCodedRankingTerm() {
        seedUnrelatedPublishedCandidates();
        insert(MODEL_ID, "L1_MODEL", "支付说明", "支付说明：已支付金额按支付日期记录。 ");
        insert(RULE_ID, "L3_RULE", "退款规则", "退款规则：退款后如何解释支付金额。 ");

        List<String> ruleIds = knowledge.recall("退款规则", METRIC).stream().map(Evidence::id).toList();
        List<String> contextIds = knowledge.context("支付说明", METRIC).stream().map(Evidence::id).toList();
        List<String> modelWorkbenchIds = workbench.recall(METRIC, "支付说明").stream().map(Semantic.KnowledgeHit::id).toList();
        List<String> ruleWorkbenchIds = workbench.recall(METRIC, "退款规则").stream().map(Semantic.KnowledgeHit::id).toList();

        assertSoftly(softly -> {
            softly.assertThat(ruleIds).as("主链规则召回应命中退款规则").contains(RULE_ID);
            softly.assertThat(contextIds).as("主链上下文应命中支付说明").contains(MODEL_ID);
            softly.assertThat(modelWorkbenchIds).as("工作台应命中支付说明").contains(MODEL_ID);
            softly.assertThat(ruleWorkbenchIds).as("工作台应命中退款规则").contains(RULE_ID);
        });
    }

    @Test
    void longNaturalLanguagePrefixDoesNotHideTheRelevantEndingPhrase() {
        seedUnrelatedPublishedCandidates();
        insert(RULE_ID, "L3_RULE", "退款规则", "退款规则：退款后如何解释支付金额。 ");
        String question = "请你根据本项目业务流程仔细解释一下退款规则";

        List<String> ruleIds = knowledge.recall(question, METRIC).stream().map(Evidence::id).toList();
        List<String> workbenchIds = workbench.recall(METRIC, question).stream().map(Semantic.KnowledgeHit::id).toList();

        assertSoftly(softly -> {
            softly.assertThat(ruleIds).as("长前缀问题的主链召回应命中句尾退款规则").contains(RULE_ID);
            softly.assertThat(workbenchIds).as("长前缀问题的工作台召回应命中句尾退款规则").contains(RULE_ID);
        });
    }

    private void seedUnrelatedPublishedCandidates() {
        // Fixed IDs make the relevant documents sort after the unrelated candidates.
        // active=TRUE represents already-published knowledge without random draft IDs.
        for (int i = 0; i < 33; i++) {
            insert("r21-a-model-%02d".formatted(i), "L1_MODEL", "普通说明", "一般背景记录。 ");
        }
        for (int i = 0; i < 25; i++) {
            insert("r21-a-rule-%02d".formatted(i), "L3_RULE", "普通规则", "一般背景记录。 ");
        }
    }

    private void insert(String id, String layer, String title, String content) {
        db.update("INSERT INTO knowledge_document(id,title,content,version,metric_code,layer_code,active) VALUES(?,?,?,?,?,?,TRUE)",
            id, title, content, "1", METRIC, layer);
    }
}
