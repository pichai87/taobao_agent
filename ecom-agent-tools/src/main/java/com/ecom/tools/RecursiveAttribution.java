package com.ecom.tools;

import com.ecom.domain.Analysis.Daily;
import com.ecom.domain.BusinessException;
import com.ecom.domain.Diagnosis;
import com.ecom.domain.Diagnosis.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Bounded, deterministic GMV diagnosis for the existing category daily read model.
 * Three-factor Shapley averages all six substitution orders. Each factor is then
 * decomposed into category components; ratio components use the same global denominator.
 * This prevents the invalid operation of adding category CVR/AOV ratios together.
 */
public final class RecursiveAttribution {
    private static final MathContext MC = new MathContext(40, RoundingMode.HALF_EVEN);
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int[][] ORDERS = {{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};

    public Tree diagnose(List<Daily> current, List<Daily> previous) {
        return diagnose(current, previous, Options.defaults());
    }

    public Tree diagnose(List<Daily> current, List<Daily> previous, Options options) {
        Objects.requireNonNull(options, "options");
        Period now = period(current), old = period(previous);
        Totals n = now.total(), p = old.total();
        State state = new State(now, old, options);
        List<String> limitations = new ArrayList<>(List.of(
                "数值贡献是恒等式拆分，不证明活动、库存或投放等业务因果。",
                "UV 沿用品类客群互斥的合成数据假设；真实跨品类 UV 必须先去重。",
                "仅支持 GMV、UV、订单数与品类；不是任意注册公式或任意业务维度的诊断引擎。",
                "CVR=订单数/UV；AOV=GMV/订单数；比率品类分支使用总分母，包含结构变化。",
                "remainingContribution 为未继续展开的金额；覆盖率以绝对贡献之和计算，避免正负抵消。"));

        StopReason forced = StopReason.NONE;
        if (now.date().equals(old.date())) {
            forced = StopReason.SAME_PERIOD;
            limitations.add("两期为同一天，不把同日比较解释为时间变化，停止诊断。");
        } else if (!now.categories().keySet().equals(old.categories().keySet())) {
            forced = StopReason.INCOMPLETE_CATEGORY_DATA;
            limitations.add("两期品类不齐全，缺失行不能自动当作零，停止因素与品类拆分。");
        } else if (n.uv().signum() == 0 || p.uv().signum() == 0
                || n.orders().signum() == 0 || p.orders().signum() == 0) {
            forced = StopReason.ZERO_DENOMINATOR;
            limitations.add("至少一期 UV 或订单数为零，CVR/AOV 恒等式不可完整计算，停止拆分。");
        }
        state.forced = forced;
        Spec root = new Spec(Kind.ROOT, "GMV", "GMV 变化", "ALL", "CNY", p.gmv(), n.gmv(),
                money(n.gmv().subtract(p.gmv())), BigDecimal.ONE, "GMV = UV × (ORDERS / UV) × (GMV / ORDERS)");
        Node node = expand(root, 0, new HashSet<>(), state);
        return new Tree("GMV_SHAPLEY_3_FACTOR_V1", now.date(), old.date(), options, node, state.used, limitations);
    }

    private Node expand(Spec spec, int depth, Set<String> ancestors, State state) {
        state.used++;
        String id = "diagnosis-" + state.used;
        String key = spec.metric() + "|" + spec.scope();
        if (ancestors.contains(key)) return node(id, spec, depth, StopReason.CYCLE, List.of());
        if (spec.kind() == Kind.ROOT && state.forced != StopReason.NONE)
            return node(id, spec, depth, state.forced, List.of());
        if (depth >= state.options.maxDepth()) return node(id, spec, depth, StopReason.MAX_DEPTH, List.of());
        if (state.used >= state.options.maxNodes()) return node(id, spec, depth, StopReason.NODE_BUDGET, List.of());
        if (spec.kind() == Kind.LEAF) return node(id, spec, depth, StopReason.LEAF, List.of());

        List<Spec> children = candidates(spec, state);
        if (children.isEmpty()) return node(id, spec, depth, StopReason.LEAF, List.of());
        // Keep every rounding adjustment explicit inside a deterministic visible contribution.
        children = conserve(children, spec.amount());
        children = children.stream().sorted(Comparator.comparing((Spec c) -> c.amount().abs()).reversed()
                .thenComparing(Spec::metric).thenComparing(Spec::scope)).toList();
        BigDecimal mass = children.stream().map(c -> c.amount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (mass.signum() == 0) return node(id, spec, depth, StopReason.ZERO_CONTRIBUTION, List.of());

        Set<String> path = new HashSet<>(ancestors);
        path.add(key);
        List<Node> visible = new ArrayList<>();
        BigDecimal covered = BigDecimal.ZERO;
        StopReason stop = StopReason.NONE;
        for (Spec child : children) {
            if (child.amount().signum() == 0) continue;
            if (state.used >= state.options.maxNodes()) { stop = StopReason.NODE_BUDGET; break; }
            if (child.amount().abs().compareTo(state.options.minContribution()) < 0) {
                stop = StopReason.MIN_CONTRIBUTION; break;
            }
            if (covered.compareTo(mass.multiply(state.options.coverage(), MC)) >= 0) {
                stop = StopReason.COVERAGE; break;
            }
            visible.add(expand(child, depth + 1, path, state));
            covered = covered.add(child.amount().abs());
        }
        return node(id, spec, depth, stop, visible);
    }

    private List<Spec> candidates(Spec spec, State state) {
        Totals n = state.now.total(), p = state.old.total();
        if (spec.kind() == Kind.ROOT) return factors(n, p);
        if (Set.of(Kind.UV, Kind.CVR, Kind.AOV).contains(spec.kind())) {
            List<Spec> result = new ArrayList<>();
            for (String category : state.now.categories().keySet()) {
                Totals cn = state.now.categories().get(category), cp = state.old.categories().get(category);
                BigDecimal before, after;
                Kind kind;
                String unit, formula;
                if (spec.kind() == Kind.UV) {
                    before = cp.uv(); after = cn.uv(); kind = Kind.LEAF; unit = "visitors";
                    formula = "UV = Σ category UV (disjoint visitors assumption)";
                } else if (spec.kind() == Kind.CVR) {
                    before = divide(cp.orders(), p.uv()); after = divide(cn.orders(), n.uv());
                    kind = Kind.CATEGORY_CVR; unit = "orders/total_visitors";
                    formula = "category component = category ORDERS / total UV";
                } else {
                    before = divide(cp.gmv(), p.orders()); after = divide(cn.gmv(), n.orders());
                    kind = Kind.CATEGORY_AOV; unit = "CNY/total_orders";
                    formula = "category component = category GMV / total ORDERS";
                }
                BigDecimal amount = after.subtract(before).multiply(spec.coefficient(), MC);
                result.add(new Spec(kind, spec.metric(), category + " 对 " + spec.label() + " 的贡献",
                        category, unit, before, after, amount, spec.coefficient(), formula));
            }
            return result;
        }
        if (spec.kind() == Kind.CATEGORY_CVR || spec.kind() == Kind.CATEGORY_AOV) {
            boolean cvr = spec.kind() == Kind.CATEGORY_CVR;
            Totals cn = state.now.categories().get(spec.scope()), cp = state.old.categories().get(spec.scope());
            BigDecimal n0 = cvr ? cp.orders() : cp.gmv(), n1 = cvr ? cn.orders() : cn.gmv();
            BigDecimal d0 = cvr ? p.uv() : p.orders(), d1 = cvr ? n.uv() : n.orders();
            // Exact two-player ratio Shapley: average numerator-first and denominator-first.
            BigDecimal numeratorDelta = n1.subtract(n0).multiply(
                    divide(BigDecimal.ONE, d0).add(divide(BigDecimal.ONE, d1)), MC)
                    .divide(BigDecimal.valueOf(2), MC).multiply(spec.coefficient(), MC);
            BigDecimal denominatorDelta = n0.add(n1).multiply(
                    divide(BigDecimal.ONE, d1).subtract(divide(BigDecimal.ONE, d0)), MC)
                    .divide(BigDecimal.valueOf(2), MC).multiply(spec.coefficient(), MC);
            return List.of(
                    new Spec(Kind.LEAF, cvr ? "ORDERS" : "GMV", spec.scope() + (cvr ? "订单数" : "GMV"),
                            spec.scope(), cvr ? "orders" : "CNY", n0, n1, numeratorDelta, BigDecimal.ONE,
                            "weighted ratio numerator contribution (two-order average)"),
                    new Spec(Kind.LEAF, cvr ? "UV" : "ORDERS", cvr ? "总 UV 分母变化" : "总订单数分母变化",
                            "ALL", cvr ? "visitors" : "orders", d0, d1, denominatorDelta, BigDecimal.ONE,
                            "weighted ratio denominator contribution (two-order average)"));
        }
        return List.of();
    }

    private List<Spec> factors(Totals now, Totals old) {
        BigDecimal[] before = {old.uv(), divide(old.orders(), old.uv()), divide(old.gmv(), old.orders())};
        BigDecimal[] after = {now.uv(), divide(now.orders(), now.uv()), divide(now.gmv(), now.orders())};
        BigDecimal[] coefficients = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (int[] order : ORDERS) {
            boolean[] changed = new boolean[3];
            for (int factor : order) {
                BigDecimal coefficient = BigDecimal.ONE;
                for (int other = 0; other < 3; other++) {
                    if (factor != other) coefficient = coefficient.multiply(changed[other] ? after[other] : before[other], MC);
                }
                coefficients[factor] = coefficients[factor].add(coefficient);
                changed[factor] = true;
            }
        }
        String[] metrics = {"UV", "CVR", "AOV"};
        String[] labels = {"访客数", "订单转化率", "平均订单金额"};
        String[] units = {"visitors", "orders/visitors", "CNY/orders"};
        Kind[] kinds = {Kind.UV, Kind.CVR, Kind.AOV};
        List<Spec> result = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BigDecimal coefficient = coefficients[i].divide(BigDecimal.valueOf(6), MC);
            result.add(new Spec(kinds[i], metrics[i], labels[i], "ALL", units[i], before[i], after[i],
                    after[i].subtract(before[i]).multiply(coefficient, MC), coefficient,
                    "Shapley: average marginal GMV difference over all 6 factor substitution orders"));
        }
        return result;
    }

    private List<Spec> conserve(List<Spec> values, BigDecimal expected) {
        List<Spec> rounded = new ArrayList<>();
        BigDecimal sum = ZERO;
        int largest = 0;
        for (Spec value : values) {
            Spec r = value.withAmount(money(value.amount()));
            if (!rounded.isEmpty() && r.amount().abs().compareTo(rounded.get(largest).amount().abs()) > 0)
                largest = rounded.size();
            rounded.add(r); sum = sum.add(r.amount());
        }
        if (!rounded.isEmpty()) {
            Spec chosen = rounded.get(largest);
            rounded.set(largest, chosen.withAmount(chosen.amount().add(expected.subtract(sum))));
        }
        return rounded;
    }

    private Node node(String id, Spec spec, int depth, StopReason stop, List<Node> children) {
        BigDecimal amount = money(spec.amount());
        BigDecimal shown = children.stream().map(Node::contribution).reduce(ZERO, BigDecimal::add);
        return new Node(id, spec.metric(), spec.label(), spec.scope(),
                spec.unit(), display(spec.previous()), display(spec.current()), amount, spec.formula(), depth,
                stop, amount.subtract(shown), children);
    }

    private Period period(List<Daily> rows) {
        if (rows == null || rows.isEmpty()) throw new BusinessException("DIAGNOSIS_NO_DATA");
        LocalDate date = null;
        Map<String, Totals> categories = new TreeMap<>();
        for (Daily row : rows) {
            if (row == null || row.date() == null || row.category() == null || row.category().isBlank()
                    || row.gmv() == null || row.gmv().signum() < 0 || row.orders() < 0 || row.uv() < 0)
                throw new BusinessException("DIAGNOSIS_INVALID_DATA");
            if ((row.gmv().signum() > 0 && row.orders() == 0) || (row.orders() > 0 && row.uv() == 0))
                throw new BusinessException("DIAGNOSIS_INCONSISTENT_DATA");
            if (date == null) date = row.date();
            if (!date.equals(row.date())) throw new BusinessException("DIAGNOSIS_INVALID_PERIOD");
            // This read model is daily x category; duplicate rows would double-count UV.
            if (categories.containsKey(row.category())) throw new BusinessException("DIAGNOSIS_DUPLICATE_CATEGORY");
            categories.put(row.category(), new Totals(row.gmv(), BigDecimal.valueOf(row.orders()), BigDecimal.valueOf(row.uv())));
        }
        Totals total = new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        for (Totals value : categories.values()) total = total.add(value);
        return new Period(date, Collections.unmodifiableMap(categories), total);
    }

    private static BigDecimal divide(BigDecimal n, BigDecimal d) { return n.divide(d, MC); }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal display(BigDecimal value) { return value.setScale(12, RoundingMode.HALF_UP).stripTrailingZeros(); }

    private enum Kind { ROOT, UV, CVR, AOV, CATEGORY_CVR, CATEGORY_AOV, LEAF }
    private record Spec(Kind kind, String metric, String label, String scope, String unit, BigDecimal previous,
                        BigDecimal current, BigDecimal amount, BigDecimal coefficient, String formula) {
        Spec withAmount(BigDecimal changed) {
            return new Spec(kind, metric, label, scope, unit, previous, current, changed, coefficient, formula);
        }
    }
    private record Totals(BigDecimal gmv, BigDecimal orders, BigDecimal uv) {
        Totals add(Totals other) { return new Totals(gmv.add(other.gmv), orders.add(other.orders), uv.add(other.uv)); }
    }
    private record Period(LocalDate date, Map<String, Totals> categories, Totals total) {}
    private static final class State {
        final Period now, old;
        final Options options;
        int used;
        StopReason forced;
        State(Period now, Period old, Options options) { this.now = now; this.old = old; this.options = options; }
    }
}
