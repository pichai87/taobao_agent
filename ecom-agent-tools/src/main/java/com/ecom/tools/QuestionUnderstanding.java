package com.ecom.tools;

import com.ecom.domain.BusinessException;
import com.ecom.domain.Understanding.*;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic constraint extraction, not LLM inference or permission to execute a query. */
public final class QuestionUnderstanding {
    private QuestionUnderstanding() {}
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> CODES = Set.of("GMV", "ORDERS", "UV", "CVR", "AOV");
    private static final Map<String, Integer> CHINESE = Map.of(
        "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
        "六", 6, "七", 7, "八", 8, "九", 9, "十", 10);
    private static final String N = "[+-]?\\d+(?:\\.\\d+)?|[零〇一二三四五六七八九十百千万两几若干]+";
    private static final Pattern RANGE = Pattern.compile(
        "(?:过去|最近|近)\\s*(" + N + ")\\s*天(?:\\s*[（(]?\\s*(不含今天|不包括今天|含今天|包括今天|截至昨天|截止昨天)\\s*[)）]?)?");
    private static final Pattern AGO = Pattern.compile("(" + N + ")\\s*天前");
    private static final Pattern UNCLEAR_BEFORE = Pattern.compile("前\\s*(" + N + ")\\s*天");
    private static final Pattern ISO = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern CHINESE_DATE = Pattern.compile("(?<!\\d)(\\d{4})年(\\d{1,2})月(\\d{1,2})日?");
    private static final Pattern THIS_YEAR = Pattern.compile("今年(\\d{1,2})月(\\d{1,2})日?");
    private static final Pattern YEARLESS_DATE = Pattern.compile("(?<![年\\d])(\\d{1,2})月(\\d{1,2})日?");
    private static final Pattern RELATIVE = Pattern.compile("今天|昨天|前天");
    private static final Pattern VAGUE_TIME = Pattern.compile("本周|上周|下周|本月|上月|下月|近期|这几天|最近一段|去年(?!同期|同一天)");
    private static final String FILTER_TARGET = "(?:GMV|ORDERS|UV|CVR|AOV|家电|美妆|食品|成交额|订单数|访客数|转化率|客单价)";
    private static final Pattern NEGATED_PREFIX = Pattern.compile(
        "(?i)(?:不要(?:看|查)?|不是|不含|不包括|排除|剔除|去掉|去除|除去|除了|除|不查|不看|忽略|非)\\s*" + FILTER_TARGET);
    private static final Pattern NEGATED_SUFFIX = Pattern.compile("(?i)" + FILTER_TARGET + "\\s*(?:以外|之外|除外)");
    private static final Map<String, Pattern> METRIC_PATTERNS = Map.of(
        "GMV", pattern("GMV", "支付成交额|成交金额|成交额|交易额|支付金额|支付订单金额"),
        "ORDERS", pattern("ORDERS", "支付订单笔数|订单数量|订单数|订单量|支付单量|(?:多少|几)笔订单|(?:多少|几)单"),
        "UV", pattern("UV", "独立访客数|独立访客|访客人数|访客数"),
        "CVR", pattern("CVR", "转化率"),
        "AOV", pattern("AOV", "客单价|平均订单金额|平均订单额"));
    private static final Map<String, Pattern> CATEGORIES = Map.of(
        "appliances", pattern("appliances", "家电"),
        "beauty", pattern("beauty", "美妆"),
        "food", pattern("food", "食品"));

    public static Resolved resolve(String question, LocalDate explicitDate, LocalDate explicitCompareDate,
                                   String explicitMetric, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        var issues = new Issues();
        var rules = new ArrayList<String>();
        if (question == null || question.isBlank() || question.length() > 2000) {
            issues.add("INVALID_QUESTION", "问题不能为空，且不能超过 2000 字。");
            return new Resolved(List.of(), List.of(), explicitDate, explicitCompareDate,
                null, null, null, null, issues.values(), rules);
        }
        String q = question.trim();
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        List<String> metrics = matches(q, METRIC_PATTERNS);
        String formMetric = explicitMetric == null || explicitMetric.isBlank() ? null : explicitMetric.trim().toUpperCase(Locale.ROOT);
        if (formMetric != null && !CODES.contains(formMetric))
            issues.add("UNSUPPORTED_EXPLICIT_METRIC", "表单指标必须是已支持的 GMV、ORDERS、UV、CVR 或 AOV。", formMetric);
        else if (formMetric != null && metrics.isEmpty()) metrics = List.of(formMetric);
        else if (formMetric != null && (metrics.size() != 1 || !metrics.contains(formMetric)))
            issues.add("METRIC_FORM_CONFLICT", "文本指标与表单的单个指标不一致，请明确要查询的全部指标。", metrics.toArray(String[]::new));
        if (q.matches("(?is).*(净利润|利润|毛利|退款金额|退款额|\\bROI\\b|\\bPV\\b|访问量).*"))
            issues.add("UNSUPPORTED_METRIC", "问题包含尚未支持或不能直接当作 UV 的指标，请选择明确口径。");

        Integer top = null;
        if (Pattern.compile("(?i)(?<![a-z_])top\\s*\\d+(?:e[+-]?\\d+|\\s*[-~至到]\\s*\\d+)").matcher(q).find() ||
            Pattern.compile("前\\s*(?:" + N + ")\\s*[-~至到]\\s*(?:" + N + ")\\s*(?:名|位|品类)").matcher(q).find())
            issues.add("INVALID_TOP_N", "排名需要单个明确整数，不支持科学计数或数量区间。");
        try { var n = QuestionConstraints.topN(q); if (n.isPresent()) top = n.getAsInt(); }
        catch (BusinessException invalid) { issues.add("INVALID_TOP_N", "排名数量需明确为 1 到 100，中文支持一至十。"); }
        List<Intent> intents = intents(q, top);
        boolean numeric = intents.stream().anyMatch(i -> Set.of(Intent.QUERY, Intent.COMPARE, Intent.ATTRIBUTION, Intent.TREND, Intent.TOP_N).contains(i));
        if (numeric && (NEGATED_PREFIX.matcher(q).find() || NEGATED_SUFFIX.matcher(q).find()))
            issues.add("NEGATED_CONSTRAINT_UNSUPPORTED", "否定或排除式指标/品类约束需要澄清，请明确正向选择，不能当成包含条件。");
        if (numeric && metrics.size() > 1 && contains(q, "除以", "乘以", "相除", "之比", "/", "÷", "×"))
            issues.add("METRIC_RELATION_UNSUPPORTED", "指标关系表达尚未映射到已注册公式，请明确查询多个指标还是计算新的比值。");
        if (metrics.isEmpty() && (numeric || intents.contains(Intent.DEFINITION)))
            issues.add("MISSING_METRIC", "没有明确指标，请选择 GMV、订单数、UV、CVR 或 AOV。");

        Temporal time = temporal(q, today, explicitDate, issues, rules);
        LocalDate date = mergeDate(time.date, explicitDate, "DATE_FORM_CONFLICT", "目标日期", issues);
        LocalDate compare = mergeDate(time.compare, explicitCompareDate, "COMPARE_DATE_FORM_CONFLICT", "对比日期", issues);
        boolean comparing = intents.contains(Intent.COMPARE) || intents.contains(Intent.ATTRIBUTION);
        if (comparing && metrics.size() > 1 && metricsAcrossDateSegments(q, time.points))
            issues.add("METRIC_DATE_BINDING_AMBIGUOUS",
                "不同日期附近出现不同指标，当前查询计划不能逐一绑定指标与日期；请拆成两个问题或明确每个指标都要比较两个日期。");
        if (numeric && date == null) issues.add("MISSING_DATE", "没有明确目标日期；不会默认使用今天。");
        if (comparing) {
            boolean daily = q.contains("日环比") || q.contains("按天环比") || q.contains("逐日环比");
            boolean yearly = q.contains("日同比") || q.contains("去年同一天") || q.contains("去年同期");
            if ((q.contains("环比") && !daily) || (q.contains("同比") && !yearly))
                issues.add("COMPARISON_PERIOD_REQUIRED", "环比/同比需要明确周期；请说明日环比、日同比或具体对比日期和周期。");
            if ((daily || yearly) && date != null) {
                LocalDate expected = null;
                try { expected = daily ? date.minusDays(1) : LocalDate.of(date.getYear() - 1, date.getMonthValue(), date.getDayOfMonth()); }
                catch (DateTimeException invalid) { issues.add("COMPARISON_DATE_AMBIGUOUS", "去年没有同月同日，请明确闰日的对比规则。"); }
                if (expected != null) {
                    if (compare == null) compare = expected;
                    else if (!compare.equals(expected)) issues.add("COMPARISON_PERIOD_CONFLICT", "对比日期不符合问题指定的日环比/日同比。", compare.toString(), expected.toString());
                    rules.add(daily ? "日环比：目标日与前一日比较。" : "日同比：目标日与去年同月同日比较，闰日缺失时澄清。");
                }
            }
            if (compare == null) issues.add("MISSING_COMPARE_DATE", "比较/归因缺少基准日期，请明确对比日。");
        }
        if (intents.contains(Intent.TREND) && !time.range)
            issues.add("MISSING_TREND_RANGE", "趋势问题需要明确起止日期或最近 N 天，不默认猜七天。");
        LocalDate start = time.start != null ? time.start : date;
        LocalDate end = time.end != null ? time.end : date;
        String category = category(q, issues);
        return new Resolved(metrics, intents, date, compare, start, end, category, top, issues.values(), rules);
    }

    private static List<Intent> intents(String q, Integer top) {
        var result = new ArrayList<Intent>();
        boolean definition = contains(q, "定义", "口径", "是什么", "怎么算", "如何计算");
        boolean ops = contains(q, "任务状态", "服务状态", "失败任务");
        boolean query = contains(q, "查询", "查看", "查一下", "统计", "多少", "数值", "总额") &&
            (!ops || !matches(q, METRIC_PATTERNS).isEmpty());
        if (definition) result.add(Intent.DEFINITION);
        if (query) result.add(Intent.QUERY);
        if (contains(q, "归因", "原因", "为什么", "为何")) result.add(Intent.ATTRIBUTION);
        boolean comparison = contains(q, "对比", "比较", "相比", "相较", "环比", "同比", "去年同一天", "去年同期") ||
            q.matches(".*比(?:今天|昨天|前天|\\d{4}).*");
        if (comparison && (!definition || query || q.matches("(?s).*(?:再|并|然后|同时|以及).{0,6}(?:对比|比较|环比|同比).*"))) result.add(Intent.COMPARE);
        if (contains(q, "趋势", "走势")) result.add(Intent.TREND);
        if (top != null || contains(q, "排名", "排行") || Pattern.compile("(?i)(?<![a-z_])top(?![a-z_])").matcher(q).find()) result.add(Intent.TOP_N);
        if (ops) result.add(Intent.OPS);
        if (result.isEmpty()) result.add(contains(q, "你好", "您好") ? Intent.OTHER : Intent.QUERY);
        return List.copyOf(result);
    }

    private static Temporal temporal(String q, LocalDate today, LocalDate formDate, Issues issues, List<String> rules) {
        var value = new Temporal();
        boolean[] used = new boolean[q.length()];
        Matcher ranges = RANGE.matcher(q);
        while (ranges.find()) {
            mark(used, ranges.start(), ranges.end());
            Integer days = days(ranges.group(1), issues);
            if (days == null) continue;
            if (value.range) { issues.add("MULTIPLE_DATE_RANGES", "本次有多个时间区间，请拆分或明确每个子任务的日期。"); continue; }
            boolean exclude = ranges.group(2) != null && contains(ranges.group(2), "不含", "不包括", "昨天");
            value.end = today.minusDays(exclude ? 1 : 0);
            value.start = value.end.minusDays(days - 1L); value.date = value.end; value.range = true;
            rules.add("相对时间以 Asia/Shanghai 的 " + today + " 为基准；过去/最近 N 天" + (exclude ? "按明确要求不含今天。" : "含今天。"));
        }
        Matcher unclear = UNCLEAR_BEFORE.matcher(q);
        while (unclear.find()) if (!used[unclear.start()]) {
            mark(used, unclear.start(), unclear.end());
            issues.add("DATE_RANGE_AMBIGUOUS", "‘前 N 天’可能表示区间或单日，请写‘最近 N 天’或‘N 天前’。");
        }
        var points = new ArrayList<Point>();
        dates(q, ISO, used, points, issues, 0);
        dates(q, CHINESE_DATE, used, points, issues, 0);
        dates(q, THIS_YEAR, used, points, issues, today.getYear());
        Matcher partial = YEARLESS_DATE.matcher(q);
        while (partial.find()) if (!used[partial.start()]) {
            mark(used, partial.start(), partial.end());
            issues.add("DATE_YEAR_REQUIRED", "中文日期缺少年份，请写完整年份或‘今年’。");
        }
        Matcher ago = AGO.matcher(q);
        while (ago.find()) if (!used[ago.start()]) {
            mark(used, ago.start(), ago.end()); Integer days = days(ago.group(1), issues);
            if (days != null) points.add(new Point(ago.start(), ago.end(), today.minusDays(days)));
            rules.add("N 天前表示单日，以 Asia/Shanghai 的 " + today + " 为基准。");
        }
        Matcher relative = RELATIVE.matcher(q);
        while (relative.find()) if (!used[relative.start()]) {
            int offset = relative.group().equals("今天") ? 0 : relative.group().equals("昨天") ? 1 : 2;
            points.add(new Point(relative.start(), relative.end(), today.minusDays(offset)));
            rules.add("今天/昨天/前天以 Asia/Shanghai 的 " + today + " 为基准。");
        }
        if (VAGUE_TIME.matcher(q).find()) issues.add("UNSUPPORTED_TIME_EXPRESSION", "该时间表达尚无明确区间规则，请填写完整日期范围。");
        points.sort(Comparator.comparingInt(Point::start));
        if (points.size() > 2) {
            var unique = new LinkedHashMap<LocalDate, Point>(); points.forEach(p -> unique.putIfAbsent(p.date, p));
            points = new ArrayList<>(unique.values());
        }
        if (value.range) {
            if (points.stream().anyMatch(p -> !p.date.equals(value.end)))
                issues.add("DATE_RANGE_CONFLICT", "文本中的其他日期与最近 N 天区间不同，请明确是哪一个任务。");
        } else if (points.size() == 1) {
            Point p = points.getFirst();
            if (label(q, p, "对比日|基准日") || q.substring(0, p.start).matches("(?s).*(?:与|和|相比)\\s*$") && q.contains("相比")) value.compare = p.date;
            else value.date = p.date;
        } else if (points.size() == 2) {
            Point a = points.get(0), b = points.get(1);
            String between = q.substring(a.end, b.start);
            if (between.matches("\\s*(?:至|到|~|～|—)\\s*")) {
                if (b.date.isBefore(a.date) || ChronoUnit.DAYS.between(a.date, b.date) + 1 > 366)
                    issues.add("INVALID_DATE_RANGE", "日期区间需正序且不超过 366 天。");
                value.start = a.date; value.end = b.date; value.date = b.date; value.range = true;
            } else if (label(q, a, "对比日|基准日") && label(q, b, "目标日|统计日") ||
                q.substring(0, a.start).matches("(?s).*(?:与|和)\\s*$") && between.contains("相比") ||
                q.substring(0, a.start).matches("(?s).*相比\\s*$")) {
                value.date = b.date; value.compare = a.date;
            } else if (label(q, a, "目标日|统计日") && label(q, b, "对比日|基准日") ||
                contains(between, "对比", "比较", "比", "相较") ||
                between.contains("和") && q.substring(b.end).contains("相比")) {
                value.date = a.date; value.compare = b.date;
            } else if (formDate != null && (formDate.equals(a.date) || formDate.equals(b.date))) {
                value.date = formDate; value.compare = formDate.equals(a.date) ? b.date : a.date;
                rules.add("文本列出两个日期但未标明目标日，按匹配的表单目标日期确定方向。");
            } else issues.add("DATE_ROLES_AMBIGUOUS", "文本有两个日期但没有明确目标/对比或起止关系，请标注日期角色。", a.date.toString(), b.date.toString());
        } else if (points.size() > 2) issues.add("MULTIPLE_DATE_TASKS", "检测到多个不同日期，请为各子任务分别确认日期。");
        value.points = List.copyOf(points);
        return value;
    }

    private static boolean metricsAcrossDateSegments(String q, List<Point> dates) {
        if (dates.size() < 2) return false;
        Map<Integer, Set<String>> metricsBySegment = new HashMap<>();
        for (var entry : METRIC_PATTERNS.entrySet()) {
            Matcher metric = entry.getValue().matcher(q);
            while (metric.find()) {
                int segment = 0;
                for (Point date : dates) if (date.end <= metric.start()) segment++;
                metricsBySegment.computeIfAbsent(segment, ignored -> new HashSet<>()).add(entry.getKey());
            }
        }
        return metricsBySegment.size() > 1 &&
            metricsBySegment.values().stream().distinct().count() > 1;
    }

    private static void dates(String q, Pattern pattern, boolean[] used, List<Point> points, Issues issues, int year) {
        Matcher matcher = pattern.matcher(q);
        while (matcher.find()) if (!used[matcher.start()]) {
            mark(used, matcher.start(), matcher.end());
            try {
                LocalDate date = year == 0 ? LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))) :
                    LocalDate.of(year, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
                points.add(new Point(matcher.start(), matcher.end(), date));
            } catch (DateTimeException invalid) { issues.add("INVALID_DATE", "日期不存在，请核对年月日。", matcher.group()); }
        }
    }

    private static String category(String q, Issues issues) {
        List<String> found = matches(q, CATEGORIES);
        if (contains(q, "全部品类", "所有品类", "各个品类", "所有类目")) return null;
        if (found.size() > 1) { issues.add("MULTIPLE_CATEGORIES", "当前过滤契约只支持一个品类，请明确或拆分。", found.toArray(String[]::new)); return null; }
        if (found.size() == 1) return found.getFirst();
        Matcher explicit = Pattern.compile("(?:品类|类目)\\s*(?:为|是|=|:|：)\\s*([\\p{IsHan}a-zA-Z_]+)").matcher(q);
        if (explicit.find()) issues.add("UNSUPPORTED_CATEGORY", "该品类不在当前家电、美妆、食品词表内。", explicit.group(1));
        return null;
    }

    private static LocalDate mergeDate(LocalDate text, LocalDate form, String code, String label, Issues issues) {
        if (text != null && form != null && !text.equals(form)) issues.add(code, label + "在文本和表单中冲突，请确认后执行。", text.toString(), form.toString());
        return text != null ? text : form;
    }
    private static Integer days(String token, Issues issues) {
        Integer count = CHINESE.get(token);
        if (count == null && token.matches("[0-9]{1,3}")) count = Integer.parseInt(token);
        if (count == null || count < 1 || count > 366) { issues.add("INVALID_DAY_COUNT", "天数需明确为 1 到 366；中文支持一至十。"); return null; }
        return count;
    }
    private static boolean label(String q, Point p, String labels) { return q.substring(0, p.start).matches("(?s).*(?:" + labels + ")\\s*[:：]?\\s*$"); }
    private static void mark(boolean[] used, int start, int end) { Arrays.fill(used, start, end, true); }
    private static Pattern pattern(String code, String aliases) { return Pattern.compile("(?i)(?<![a-z_])" + code + "(?![a-z_])|" + aliases); }
    private static boolean contains(String text, String... terms) { return Arrays.stream(terms).anyMatch(text::contains); }
    private static List<String> matches(String q, Map<String, Pattern> patterns) {
        var starts = new HashMap<String, Integer>();
        patterns.forEach((code, pattern) -> { Matcher m = pattern.matcher(q); if (m.find()) starts.put(code, m.start()); });
        return starts.keySet().stream().sorted(Comparator.comparingInt((String k) -> starts.get(k)).thenComparing(k -> k)).toList();
    }
    private record Point(int start, int end, LocalDate date) {}
    private static final class Temporal { LocalDate date, compare, start, end; List<Point> points = List.of(); boolean range; }
    private static final class Issues {
        private final Map<String, Clarification> entries = new LinkedHashMap<>();
        void add(String code, String message, String... candidates) { entries.putIfAbsent(code, new Clarification(code, message, List.of(candidates))); }
        List<Clarification> values() { return List.copyOf(entries.values()); }
    }
}
