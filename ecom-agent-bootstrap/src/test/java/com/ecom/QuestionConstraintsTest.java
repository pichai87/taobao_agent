package com.ecom;

import com.ecom.domain.Analysis.Request;
import com.ecom.domain.BusinessException;
import com.ecom.knowledge.RulePlanner;
import com.ecom.tools.QuestionConstraints;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.ecom.domain.Analysis.Intent.TOP_N;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionConstraintsTest {
    @ParameterizedTest
    @CsvSource({"GMV top 1,1", "GMV TOP100,100", "GMV 前5名,5", "按GMV找前五个品类,5", "GMV前十,10"})
    void explicitRankNumbersArePreserved(String question, int expected) {
        assertThat(QuestionConstraints.topN(question)).hasValue(expected);
        assertThat(new RulePlanner().plan(request(question)).intent()).isEqualTo(TOP_N);
    }

    @ParameterizedTest @ValueSource(strings = {
        "GMV Top 0", "GMV Top 101", "GMV Top -1", "GMV Top 2.5", "GMV Top 999999999999", "GMV Top N",
        "GMV 前零名", "GMV 前101名", "GMV 前十一名", "GMV 前几名", "GMV Top 3 和 Top 5"
    })
    void illegalOrAmbiguousRankSizesAreRejected(String question) {
        assertThatThrownBy(() -> QuestionConstraints.topN(question))
            .isInstanceOf(BusinessException.class).hasMessage("INVALID_TOP_N");
    }

    @Test void unspecifiedRankingDoesNotInventNAndNonRankingDatesStayUnchanged() {
        assertThat(QuestionConstraints.topN("GMV 排名")).isEmpty();
        assertThat(QuestionConstraints.topN("查询前两天 GMV")).isEmpty();
        assertThat(QuestionConstraints.limit("GMV 排名", List.of(30, 20, 10))).containsExactly(30, 20, 10);
        assertThat(QuestionConstraints.limit("GMV Top 1", List.of(30, 20, 10))).containsExactly(30);
        assertThat(QuestionConstraints.limit("GMV Top 100", List.of(30, 20, 10))).containsExactly(30, 20, 10);
    }

    @ParameterizedTest @ValueSource(strings = {"TOP_N", "desktop", "stopping", "laptop", "metadata_top_count"})
    void unrelatedWordsAndEnumNamesAreNotExplicitRankNumbers(String question) {
        assertThat(QuestionConstraints.topN(question)).isEmpty();
        assertThat(QuestionConstraints.limit(question, List.of(30, 20, 10))).containsExactly(30, 20, 10);
    }

    @ParameterizedTest @ValueSource(strings = {
        "订单数量多少", "订单有多少", "有多少笔订单", "支付了几单", "CVR 多少", "UV趋势", "AOV是多少",
        "客单价排名", "转化率为什么下降", "访客数有多少", "利润多少", "GMV和订单数分别是多少"
    })
    void unsupportedMetricQuestionCannotSilentlyBecomeGmv(String question) {
        assertThatThrownBy(() -> QuestionConstraints.validate(request(question)))
            .isInstanceOf(BusinessException.class).hasMessage("QUESTION_METRIC_NOT_SUPPORTED");
    }

    @ParameterizedTest @ValueSource(strings = {
        "GMV 为什么下降", "GMV 按支付订单金额统计吗", "GMV 包含退款吗", "GMV Top 1", "任务状态"
    })
    void validGmvAndSupportRequestsStillPass(String question) {
        assertThatCode(() -> QuestionConstraints.validate(request(question))).doesNotThrowAnyException();
    }

    private static Request request(String question) {
        return new Request(question, LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 11), "GMV", false);
    }
}
