package com.ecom.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.ecom.domain.Analysis.*;

/** 归因（attribution）在此表示贡献拆分，不是证明业务因果。 */
public final class AttributionCalculator {
    public Summary summarize(List<Daily> rows) {
        BigDecimal gmv = rows.stream().map(Daily::gmv).reduce(BigDecimal.ZERO, BigDecimal::add);
        long orders=0,uv=0;
        try {for(Daily row:rows) {orders=Math.addExact(orders,row.orders());uv=Math.addExact(uv,row.uv());}}
        catch(ArithmeticException e) {throw new com.ecom.domain.BusinessException("METRIC_OVERFLOW");}
        return new Summary(gmv, orders, uv, divide(BigDecimal.valueOf(orders), BigDecimal.valueOf(uv)),
                divide(gmv, BigDecimal.valueOf(orders)));
    }
    public Result calculate(List<Daily> current, List<Daily> previous) {
        Summary c = summarize(current), p = previous.isEmpty() ? null : summarize(previous);
        Map<String, BigDecimal> amounts = new TreeMap<>();
        current.forEach(r -> amounts.merge(r.category(), r.gmv(), BigDecimal::add));
        previous.forEach(r -> amounts.merge(r.category(), r.gmv().negate(), BigDecimal::add));
        List<Contribution> contributions = p == null ? List.of() : amounts.entrySet().stream()
                .map(e -> new Contribution(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Contribution::delta)).toList();
        BigDecimal rate = p == null ? null : divide(c.gmv().subtract(p.gmv()), p.gmv());
        return new Result(c, p, rate, contributions, List.copyOf(current),
            current.isEmpty() || previous.isEmpty()?null:new RecursiveAttribution().diagnose(current,previous));
    }
    private BigDecimal divide(BigDecimal a, BigDecimal b) {
        return b.signum() == 0 ? null : a.divide(b, 6, RoundingMode.HALF_UP);
    }
}
