package com.ainative.v2.task;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 cron 解析(5 字段:分 时 日 月 周),支持 *、数字、列表(a,b)、区间(a-b)、步长(*&#47;n)。
 * 语义与标准 cron 一致(0-59 分、0-23 时、1-31 日、1-12 月、0-7 周,周日=0 或 7)。
 */
public final class CronExpression {
    private final List<Set> fields = new ArrayList<>();
    private final int[] bounds = {60, 24, 32, 13, 8};
    /** 是否带秒字段(6 字段 cron:秒 分 时 日 月 周) */
    private boolean withSeconds;

    private record Set(int min, int max, List<Integer> values) {}

    public static CronExpression parse(String expr) {
        CronExpression c = new CronExpression();
        String[] parts = expr.trim().split("\\s+");
        if (parts.length == 6) {
            c.withSeconds = true;
        } else if (parts.length != 5) {
            throw new IllegalArgumentException("cron 需要 5 或 6 个字段: " + expr);
        }
        int offset = c.withSeconds ? 1 : 0;
        if (c.withSeconds) {
            // 秒字段:范围 0-59
            c.fields.add(parseField(parts[0], 60));
        }
        for (int i = 0; i < 5; i++) {
            c.fields.add(parseField(parts[offset + i], c.bounds[i]));
        }
        return c;
    }

    private static Set parseField(String field, int size) {
        int min = 0;
        int max = size - 1;
        String f = field;
        List<Integer> values = new ArrayList<>();
        for (String part : f.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int step = 1;
            int slash = p.indexOf('/');
            if (slash > 0) {
                step = Integer.parseInt(p.substring(slash + 1));
                p = p.substring(0, slash);
            }
            int from;
            int to;
            if ("*".equals(p)) {
                from = min;
                to = max;
            } else if (p.contains("-")) {
                String[] ab = p.split("-");
                from = Integer.parseInt(ab[0]);
                to = Integer.parseInt(ab[1]);
            } else {
                from = Integer.parseInt(p);
                to = step > 1 ? max : from;
            }
            for (int v = from; v <= to && v <= max; v += step) {
                if (!values.contains(v)) {
                    values.add(v);
                }
            }
        }
        return new Set(min, max, values);
    }

    public boolean matches(LocalDateTime time) {
        int minuteIndex = withSeconds ? 1 : 0;
        if (withSeconds && time.getSecond() != 0) {
            return false;
        }
        return matchesField(minuteIndex, time.getMinute())
                && matchesField(minuteIndex + 1, time.getHour())
                && matchesField(minuteIndex + 2, time.getDayOfMonth())
                && matchesField(minuteIndex + 3, time.getMonthValue())
                && matchesField(minuteIndex + 4, time.getDayOfWeek().getValue() % 7);
    }

    private boolean matchesField(int index, int value) {
        Set s = fields.get(index);
        if (s.values().contains(value)) {
            return true;
        }
        // 周日兼容 7
        return index == 4 && s.values().contains(7) && value == 0;
    }
}
