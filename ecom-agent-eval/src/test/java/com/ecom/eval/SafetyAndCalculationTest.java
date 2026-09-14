package com.ecom.eval;

import com.ecom.tools.*;
import com.ecom.domain.*;
import com.ecom.knowledge.RulePlanner;
import org.junit.jupiter.api.*;
import java.util.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Stream;
import static com.ecom.domain.Analysis.*;
import static org.junit.jupiter.api.Assertions.*;

class SafetyAndCalculationTest {
    @TestFactory Stream<DynamicTest> rejectUnapprovedSql() {
        return Stream.of("DROP TABLE biz_order","DELETE FROM biz_order","UPDATE biz_order SET paid_amount=0",
            "SELECT * FROM biz_order","SELECT * FROM agent_run","SELECT SLEEP(100)",
            ApprovedSql.DAILY+"; DROP TABLE biz_order",ApprovedSql.DAILY+" -- ignored",
            "SELECT * FROM analytics_daily UNION SELECT * FROM agent_run","SELECT LOAD_FILE('/etc/passwd')",
            "SELECT * FROM analytics_daily INTO OUTFILE '/tmp/data'","SELECT * FROM information_schema.tables",
            "/* harmless */ "+ApprovedSql.DAILY,"SELECT 1","", "CALL dangerous()")
            .map(sql->DynamicTest.dynamicTest("reject: "+sql,()->assertThrows(BusinessException.class,()->ApprovedSql.validate(sql))));
    }
    @Test void templatesAreAccepted() {
        assertEquals(ApprovedSql.DAILY,ApprovedSql.validate(ApprovedSql.DAILY));
        assertEquals(ApprovedSql.TREND,ApprovedSql.validate(ApprovedSql.TREND));
    }
    @Test void singleDayDoesNotInventComparisonContributions() {
        var result=new AttributionCalculator().calculate(
            List.of(new Daily(LocalDate.now(),"a",new BigDecimal("100"),1,10)),List.of());
        assertTrue(result.contributions().isEmpty());
        assertNull(result.previous());
    }
    @Test void nullSqlIsRejectedAsBusinessError() {
        assertThrows(BusinessException.class,()->ApprovedSql.validate(null));
    }
    @Test void zeroDenominatorsBecomeNullNotInfinity() {
        var result=new AttributionCalculator().calculate(List.of(new Daily(LocalDate.now(),"a",BigDecimal.ZERO,0,0)),
            List.of(new Daily(LocalDate.now().minusDays(1),"a",BigDecimal.ZERO,0,0)));
        assertNull(result.changeRate());assertNull(result.current().cvr());assertNull(result.current().aov());
    }
    @Test void vanishedCategoryStillHasNegativeContribution() {
        var result=new AttributionCalculator().calculate(List.of(),
            List.of(new Daily(LocalDate.now(),"old",new BigDecimal("100"),1,10)));
        assertEquals(new BigDecimal("-100"),result.contributions().getFirst().delta());
    }
    @Test void rulesSupportEveryDeclaredIntent() {
        RulePlanner planner=new RulePlanner();
        Map<String,Intent> examples=Map.of("GMV",Intent.QUERY,"GMV 对比",Intent.COMPARE,"GMV 归因",Intent.ATTRIBUTION,
            "GMV 排名",Intent.TOP_N,"GMV 趋势",Intent.TREND,"GMV 定义",Intent.DEFINITION);
        examples.forEach((q,intent)->assertEquals(intent,planner.plan(new Request(q,LocalDate.now(),null,"GMV",false)).intent()));
    }
}
