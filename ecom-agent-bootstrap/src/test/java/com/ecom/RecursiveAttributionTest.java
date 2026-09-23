package com.ecom;

import com.ecom.domain.Analysis.Daily;
import com.ecom.domain.Diagnosis.*;
import com.ecom.tools.RecursiveAttribution;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RecursiveAttributionTest {
    private static final LocalDate CURRENT = LocalDate.of(2026, 9, 12);
    private static final LocalDate PREVIOUS = CURRENT.minusDays(1);
    private final RecursiveAttribution calculator = new RecursiveAttribution();

    @Test void syntheticDropHasConservedCvrCategoryAndOrderContributions() {
        Tree tree = calculator.diagnose(current(), previous());
        assertThat(tree.root().contribution()).isEqualByComparingTo("-400.00");
        Node cvr = find(tree.root(), "CVR", "ALL");
        assertThat(cvr.contribution()).isEqualByComparingTo("-400.00");
        Node appliances = find(cvr, "CVR", "家电");
        assertThat(appliances.contribution()).isEqualByComparingTo("-400.00");
        assertThat(find(appliances, "ORDERS", "家电").contribution()).isEqualByComparingTo("-400.00");
        assertThat(tree.nodeCount()).isEqualTo(count(tree.root()));
        assertConserved(tree.root());
        assertThat(tree.limitations()).anyMatch(s -> s.contains("不证明"));
    }

    @Test void allSixShapleyOrdersProduceKnownMixedFactorAmounts() {
        Tree tree = calculator.diagnose(List.of(row(CURRENT,"A","480",24,200)),
                List.of(row(PREVIOUS,"A","100",10,100)));
        // (U,C,A): (100,.10,10) -> (200,.12,20); exact Shapley: 166 2/3, 46 2/3, 166 2/3.
        assertThat(tree.root().contribution()).isEqualByComparingTo("380.00");
        assertThat(find(tree.root(),"UV","ALL").contribution()).isEqualByComparingTo("166.66");
        assertThat(find(tree.root(),"CVR","ALL").contribution()).isEqualByComparingTo("46.67");
        assertThat(find(tree.root(),"AOV","ALL").contribution()).isEqualByComparingTo("166.67");
        assertConserved(tree.root());
    }

    @Test void reversingPeriodsReversesEveryTopLevelFactorContribution() {
        List<Daily> now = List.of(row(CURRENT,"A","580.13",31,170),row(CURRENT,"B","83.22",7,41));
        List<Daily> old = List.of(row(PREVIOUS,"A","271.49",17,109),row(PREVIOUS,"B","145.04",11,70));
        Tree forward = calculator.diagnose(now,old), reverse = calculator.diagnose(old,now);
        for (String metric : List.of("UV","CVR","AOV")) {
            assertThat(find(forward.root(),metric,"ALL").contribution())
                    .isEqualByComparingTo(find(reverse.root(),metric,"ALL").contribution().negate());
        }
        assertConserved(forward.root()); assertConserved(reverse.root());
    }

    @Test void ratiosUseGlobalDenominatorsNotSumOfCategoryConversionRates() {
        Tree tree = calculator.diagnose(List.of(row(CURRENT,"A","900",9,100),row(CURRENT,"B","100",1,900)),
                List.of(row(PREVIOUS,"A","500",5,500),row(PREVIOUS,"B","500",5,500)));
        // Both periods have U=1000,O=10,GMV=1000. A changed category rate is not a changed global CVR.
        assertThat(tree.root().contribution()).isEqualByComparingTo("0.00");
        assertThat(tree.root().stopReason()).isEqualTo(StopReason.ZERO_CONTRIBUTION);
        assertConserved(tree.root());
    }

    @Test void depthAndGlobalNodeBudgetsLeaveExplicitUnexpandedAmounts() {
        for (int nodes : List.of(1,2,3,4)) {
            Tree tree = calculator.diagnose(current(),previous(),new Options(3,BigDecimal.ONE,BigDecimal.ZERO,nodes));
            assertThat(tree.nodeCount()).isLessThanOrEqualTo(nodes);
            assertConserved(tree.root());
        }
        Tree shallow = calculator.diagnose(current(),previous(),new Options(0,BigDecimal.ONE,BigDecimal.ZERO,50));
        assertThat(shallow.root().stopReason()).isEqualTo(StopReason.MAX_DEPTH);
        assertThat(shallow.root().remainingContribution()).isEqualByComparingTo("-400");
        assertThat(shallow.root().children()).isEmpty();
    }

    @Test void coverageAndThresholdDoNotHideUnshownContributions() {
        List<Daily> now = List.of(row(CURRENT,"A","480",24,200));
        List<Daily> old = List.of(row(PREVIOUS,"A","100",10,100));
        Tree cover = calculator.diagnose(now,old,new Options(3,new BigDecimal("0.4"),BigDecimal.ZERO,64));
        assertThat(cover.root().stopReason()).isEqualTo(StopReason.COVERAGE);
        assertThat(cover.root().remainingContribution().signum()).isPositive();
        assertConserved(cover.root());
        Tree threshold = calculator.diagnose(now,old,new Options(3,BigDecimal.ONE,new BigDecimal("500"),64));
        assertThat(threshold.root().stopReason()).isEqualTo(StopReason.MIN_CONTRIBUTION);
        assertThat(threshold.root().remainingContribution()).isEqualByComparingTo("380");
    }

    @Test void zeroDenominatorsAndMissingCategoriesStopWithoutInventingZeros() {
        Tree zero = calculator.diagnose(List.of(row(CURRENT,"A","0",0,0)),
                List.of(row(PREVIOUS,"A","100",1,10)));
        assertThat(zero.root().stopReason()).isEqualTo(StopReason.ZERO_DENOMINATOR);
        assertThat(zero.root().contribution()).isEqualByComparingTo("-100");
        assertThat(zero.root().children()).isEmpty();
        Tree missing = calculator.diagnose(List.of(row(CURRENT,"A","100",1,10)),
                List.of(row(PREVIOUS,"A","100",1,10),row(PREVIOUS,"B","100",1,10)));
        assertThat(missing.root().stopReason()).isEqualTo(StopReason.INCOMPLETE_CATEGORY_DATA);
        assertThat(missing.root().children()).isEmpty();
    }

    @Test void invalidDataCannotSilentlyEnterTheTree() {
        assertThatThrownBy(() -> calculator.diagnose(List.of(),previous())).hasMessage("DIAGNOSIS_NO_DATA");
        assertThatThrownBy(() -> calculator.diagnose(List.of(row(CURRENT,"A","-1",1,1)),previous()))
                .hasMessage("DIAGNOSIS_INVALID_DATA");
        assertThatThrownBy(() -> calculator.diagnose(List.of(row(CURRENT,"A","1",1,1),row(CURRENT,"A","1",1,1)),previous()))
                .hasMessage("DIAGNOSIS_DUPLICATE_CATEGORY");
        assertThatThrownBy(() -> calculator.diagnose(List.of(row(CURRENT,"A","1",1,1),row(PREVIOUS,"B","1",1,1)),previous()))
                .hasMessage("DIAGNOSIS_INVALID_PERIOD");
    }

    @Test void optionsAreBoundedAndEveryPathIsAcyclic() {
        assertThatThrownBy(() -> new Options(9,BigDecimal.ONE,BigDecimal.ZERO,10)).hasMessage("INVALID_DIAGNOSIS_OPTIONS");
        assertThatThrownBy(() -> new Options(3,BigDecimal.ZERO,BigDecimal.ZERO,10)).hasMessage("INVALID_DIAGNOSIS_OPTIONS");
        assertThatThrownBy(() -> new Options(3,BigDecimal.ONE,BigDecimal.ZERO,1001)).hasMessage("INVALID_DIAGNOSIS_OPTIONS");
        Tree tree = calculator.diagnose(List.of(row(CURRENT,"A","480",24,200)),
                List.of(row(PREVIOUS,"A","100",10,100)),new Options(8,BigDecimal.ONE,BigDecimal.ZERO,1000));
        assertAcyclic(tree.root(),new HashSet<>());
        assertThat(count(tree.root())).isLessThan(30);
    }

    @Test void categoryTotalsBeyondLongRangeRemainExactInTheDiagnosis() {
        long maximum = Long.MAX_VALUE;
        Tree tree = calculator.diagnose(List.of(row(CURRENT,"A","200",maximum,maximum),row(CURRENT,"B","200",maximum,maximum)),
                List.of(row(PREVIOUS,"A","100",maximum,maximum),row(PREVIOUS,"B","100",maximum,maximum)));
        assertThat(tree.root().contribution()).isEqualByComparingTo("200");
        assertThat(find(tree.root(),"AOV","ALL").contribution()).isEqualByComparingTo("200");
        assertConserved(tree.root());
    }

    @Test void zeroNetChangeStillShowsOffsettingFactorContributions() {
        Tree tree = calculator.diagnose(List.of(row(CURRENT,"A","100",10,200)),
                List.of(row(PREVIOUS,"A","100",10,100)));
        assertThat(tree.root().contribution()).isEqualByComparingTo("0");
        assertThat(find(tree.root(),"UV","ALL").contribution()).isEqualByComparingTo("75");
        assertThat(find(tree.root(),"CVR","ALL").contribution()).isEqualByComparingTo("-75");
        assertConserved(tree.root());
    }

    @Test void samePeriodIsReportedAndNeverInterpretedAsTimeVariation() {
        Tree tree = calculator.diagnose(current(),current());
        assertThat(tree.root().stopReason()).isEqualTo(StopReason.SAME_PERIOD);
        assertThat(tree.root().children()).isEmpty();
        assertThat(tree.limitations()).anyMatch(s -> s.contains("同一天"));
    }

    @Test void ratioRoundingAndDisplayIdsStayConsistentAcrossMultipleCategories() {
        Tree tree = calculator.diagnose(List.of(row(CURRENT,"A","811.37",31,217),row(CURRENT,"B","98.16",5,129)),
                List.of(row(PREVIOUS,"A","231.04",17,89),row(PREVIOUS,"B","120.57",8,121)));
        assertConserved(tree.root());
        List<String> ids = new ArrayList<>(); collectIds(tree.root(),ids);
        assertThat(ids).doesNotHaveDuplicates().hasSize(tree.nodeCount());
        assertThat(tree.root().contribution()).isEqualByComparingTo("557.92");
    }

    @Test void internallyInconsistentPaidRowsCannotProduceAConfidentFunnel() {
        assertThatThrownBy(() -> calculator.diagnose(List.of(row(CURRENT,"A","100",0,10)),previous()))
                .hasMessage("DIAGNOSIS_INCONSISTENT_DATA");
        assertThatThrownBy(() -> calculator.diagnose(List.of(row(CURRENT,"A","100",1,0)),previous()))
                .hasMessage("DIAGNOSIS_INCONSISTENT_DATA");
    }

    private static void collectIds(Node node,List<String> ids) {
        ids.add(node.id()); node.children().forEach(child -> collectIds(child,ids));
    }

    private static void assertConserved(Node node) {
        BigDecimal total = node.children().stream().map(Node::contribution).reduce(BigDecimal.ZERO,BigDecimal::add)
                .add(node.remainingContribution());
        assertThat(total).as(node.id()).isEqualByComparingTo(node.contribution());
        node.children().forEach(RecursiveAttributionTest::assertConserved);
    }
    private static void assertAcyclic(Node node,Set<String> ancestors) {
        String key=node.metric()+"|"+node.scope();
        assertThat(ancestors).doesNotContain(key);
        Set<String> path=new HashSet<>(ancestors);path.add(key);
        node.children().forEach(child -> assertAcyclic(child,path));
    }
    private static Node find(Node node,String metric,String scope) {
        return node.children().stream().filter(n -> n.metric().equals(metric) && n.scope().equals(scope)).findFirst().orElseThrow();
    }
    private static int count(Node node) { return 1+node.children().stream().mapToInt(RecursiveAttributionTest::count).sum(); }
    private static Daily row(LocalDate date,String category,String gmv,long orders,long uv) {
        return new Daily(date,category,new BigDecimal(gmv),orders,uv);
    }
    private static List<Daily> current() { return List.of(row(CURRENT,"家电","600",6,1000),row(CURRENT,"美妆","1000",10,1000),row(CURRENT,"食品","1000",10,1000)); }
    private static List<Daily> previous() { return List.of(row(PREVIOUS,"家电","1000",10,1000),row(PREVIOUS,"美妆","1000",10,1000),row(PREVIOUS,"食品","1000",10,1000)); }
}
