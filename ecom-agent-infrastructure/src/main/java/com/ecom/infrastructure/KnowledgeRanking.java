package com.ecom.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** Bounded lexical relevance computed in SQL before the candidate LIMIT. */
final class KnowledgeRanking {
    private static final List<String> TERMS = List.of("口径", "归因", "血缘", "SQL", "UV", "GMV", "订单", "转化", "模型");
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z][A-Za-z0-9]{1,15}");
    private static final int MAX_TERMS = 12;
    private static final List<String> GENERIC = List.of("查询", "查看", "请问", "怎么", "什么", "为什么", "今天", "昨天", "前天", "统计", "分析", "解释", "多少", "对比", "比较", "指标", "数据", "情况");

    private KnowledgeRanking() {}

    record Order(String sql, List<Object> parameters) {}

    static Order order(String question, String tieBreak) {
        if (!tieBreak.equals("id") && !tieBreak.equals("layer_code,id"))
            throw new IllegalArgumentException("Unsupported knowledge ordering");
        List<String> terms = matchedTerms(question);
        var expressions = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        // A published title named verbatim in the question must not be lost because
        // earlier words filled the bounded token budget. LOCATE has no LIKE wildcards.
        if (question != null && question.length() >= 4) {
            expressions.add("(CASE WHEN CHAR_LENGTH(title)>=4 AND LOCATE(LOWER(title), ?) > 0 THEN 25 ELSE 0 END)");
            parameters.add(question.toLowerCase(Locale.ROOT));
        }
        for (String term : terms) {
            expressions.add("(CASE WHEN LOWER(title) LIKE ? THEN 2 WHEN LOWER(content) LIKE ? THEN 1 ELSE 0 END)");
            String pattern = "%" + term.toLowerCase(Locale.ROOT) + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (expressions.isEmpty()) return new Order(tieBreak, List.of());
        return new Order("(" + String.join(" + ", expressions) + ") DESC," + tieBreak, List.copyOf(parameters));
    }

    static int score(String title, String content, String question) {
        String titleText = title.toUpperCase(Locale.ROOT), bodyText = content.toUpperCase(Locale.ROOT);
        int score = title.length() >= 4 && question != null &&
            question.toUpperCase(Locale.ROOT).contains(titleText) ? 25 : 0;
        for (String term : matchedTerms(question)) {
            if (titleText.contains(term)) score += 2;
            else if (bodyText.contains(term)) score++;
        }
        return score;
    }

    private static List<String> matchedTerms(String question) {
        if (question == null) return List.of();
        String upper = question.toUpperCase(Locale.ROOT);
        var selected = new LinkedHashSet<String>();
        for (String term : TERMS) if (upper.contains(term)) selected.add(term);
        var han = HAN.matcher(question);
        while (han.find() && selected.size() < MAX_TERMS) {
            String run = han.group();
            if (run.length() <= 8 && !GENERIC.contains(run)) selected.add(run);
            for (int i = 0; i + 1 < run.length() && selected.size() < MAX_TERMS; i++) {
                String pair = run.substring(i, i + 2);
                if (!GENERIC.contains(pair)) selected.add(pair);
            }
        }
        var latin = LATIN.matcher(question);
        while (latin.find() && selected.size() < MAX_TERMS) selected.add(latin.group().toUpperCase(Locale.ROOT));
        return selected.stream().limit(MAX_TERMS).toList();
    }
}
