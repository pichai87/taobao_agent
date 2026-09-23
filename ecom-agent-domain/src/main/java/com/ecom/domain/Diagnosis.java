package com.ecom.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Numerical attribution contracts. A contribution is not a proven business cause. */
public final class Diagnosis {
    private Diagnosis() {}

    public enum StopReason {
        NONE, LEAF, MAX_DEPTH, NODE_BUDGET, COVERAGE, MIN_CONTRIBUTION,
        ZERO_CONTRIBUTION, ZERO_DENOMINATOR, INCOMPLETE_CATEGORY_DATA, SAME_PERIOD, CYCLE
    }

    public record Options(int maxDepth, BigDecimal coverage, BigDecimal minContribution, int maxNodes) {
        public Options {
            if (maxDepth < 0 || maxDepth > 8 || maxNodes < 1 || maxNodes > 1000
                    || coverage == null || coverage.signum() <= 0 || coverage.compareTo(BigDecimal.ONE) > 0
                    || minContribution == null || minContribution.signum() < 0) {
                throw new IllegalArgumentException("INVALID_DIAGNOSIS_OPTIONS");
            }
        }

        public static Options defaults() {
            return new Options(3, BigDecimal.ONE, new BigDecimal("0.01"), 64);
        }
    }

    /**
     * previousValue/currentValue use valueUnit; contribution and remainingContribution are CNY.
     * remainingContribution is the amount not expanded into visible children, including leaf amounts.
     * Every node satisfies contribution = sum(children.contribution) + remainingContribution.
     */
    public record Node(String id, String metric, String label, String scope, String valueUnit,
                       BigDecimal previousValue, BigDecimal currentValue, BigDecimal contribution,
                       String formula, int depth, StopReason stopReason, BigDecimal remainingContribution,
                       List<Node> children) {
        public Node {
            children = List.copyOf(children);
        }
    }

    public record Tree(String method, LocalDate currentDate, LocalDate previousDate, Options options,
                       Node root, int nodeCount, List<String> limitations) {
        public Tree {
            limitations = List.copyOf(limitations);
        }
    }
}
