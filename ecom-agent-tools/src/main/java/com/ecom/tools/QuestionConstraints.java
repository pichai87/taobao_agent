package com.ecom.tools;

import com.ecom.domain.Analysis.Request;
import com.ecom.domain.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Extract only explicit supported constraints; never guess an unsupported metric or rank size. */
public final class QuestionConstraints {
    private QuestionConstraints() {}

    private static final String NUMBER = "[+-]?\\d+(?:\\.\\d+)?|[零〇一二三四五六七八九十百千万两几若干一些Nn]+";
    private static final Pattern TOP_MARKER = Pattern.compile("(?<![a-zA-Z_])top(?![a-zA-Z_])\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_TOKEN = Pattern.compile("(?:" + NUMBER + ")");
    private static final Pattern CHINESE_RANK = Pattern.compile(
        "前\\s*(" + NUMBER + ")\\s*(?=名|位|个品类|个类别|品类|类别|[，,。;；!?！？]|$)");
    private static final Map<String, Integer> CHINESE_NUMBERS = Map.of(
        "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
        "六", 6, "七", 7, "八", 8, "九", 9, "十", 10);
    private static final Pattern OTHER_METRIC_CODE = Pattern.compile(
        "(?<![a-z0-9_])(?:UV|CVR|AOV|ORDERS)(?![a-z0-9_])", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_COUNT = Pattern.compile(
        "订单(?:数(?:量)?|量)|订单(?:有)?(?:多少|几)|(?:多少|几)(?:笔|个)?(?:支付)?订单|(?:多少|几)单");
    private static final List<String> OTHER_METRIC_NAMES = List.of(
        "客单价", "转化率", "独立访客", "访客数", "访问人数", "访问量", "利润", "毛利", "净收入", "退款金额", "退款额");

    /** The analysis-run workflow currently reports GMV; reject explicit requests it cannot fulfill. */
    public static void validate(Request request) {
        if (request == null || request.question() == null) throw new BusinessException("INVALID_REQUEST");
        if (!"GMV".equals(request.metric())) throw new BusinessException("METRIC_NOT_SUPPORTED");
        String question = request.question();
        if (OTHER_METRIC_CODE.matcher(question).find() || ORDER_COUNT.matcher(question).find() ||
            OTHER_METRIC_NAMES.stream().anyMatch(question::contains))
            throw new BusinessException("QUESTION_METRIC_NOT_SUPPORTED");
        topN(question);
    }

    /** Empty means a general ranking, not an invented default N. Only Arabic 1..100 or Chinese 一..十. */
    public static OptionalInt topN(String question) {
        if (question == null) throw new BusinessException("INVALID_REQUEST");
        var values = new LinkedHashSet<Integer>();
        var markers = TOP_MARKER.matcher(question);
        while (markers.find()) {
            var number = NUMBER_TOKEN.matcher(question).region(markers.end(), question.length());
            if (!number.lookingAt()) throw new BusinessException("INVALID_TOP_N");
            values.add(parse(number.group()));
        }
        var chinese = CHINESE_RANK.matcher(question);
        while (chinese.find()) values.add(parse(chinese.group(1)));
        if (values.size() > 1) throw new BusinessException("INVALID_TOP_N");
        return values.isEmpty() ? OptionalInt.empty() : OptionalInt.of(values.iterator().next());
    }

    /** The caller sorts by the approved metric first; this method enforces the requested result size. */
    public static <T> List<T> limit(String question, List<T> ranked) {
        OptionalInt count = topN(question);
        int size = count.isPresent() ? Math.min(count.getAsInt(), ranked.size()) : ranked.size();
        return List.copyOf(ranked.subList(0, size));
    }

    private static int parse(String token) {
        Integer chinese = CHINESE_NUMBERS.get(token);
        if (chinese != null) return chinese;
        if (!token.matches("[0-9]{1,3}")) throw new BusinessException("INVALID_TOP_N");
        int value = Integer.parseInt(token);
        if (value < 1 || value > 100) throw new BusinessException("INVALID_TOP_N");
        return value;
    }
}
