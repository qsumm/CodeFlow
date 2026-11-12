package com.ainative.insights.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 交付总览近期统计：新增工单（含按日趋势）与交付吞吐（含环比）。
 * 完成口径与 {@code RequirementDeliveryService} 一致：当前处于 DONE 类状态节点、
 * 完成时间取最近一次进入 DONE 类节点的状态事件，按 workitem 去重，取消不计。
 */
@Getter
@Setter
public class DeliveryOverviewVO {
    private String startDate;
    private String endDate;
    private String timezone;
    /** 统计可视数据截止日（Asia/Shanghai 当天），不晚于 endDate。 */
    private String dataThrough;
    /** 区间天数（含首尾），用于日均计算与界面标注。 */
    private int daysInRange;
    private Created created;
    private Delivered delivered;
    /** 平均交付周期（天，一位小数）：区间内完成工单 创建→完成事件 的平均时长；区间内无完成为 null。 */
    private Double leadTimeDays;
    /** 平均交付周期环比；本期或上期无完成时无可比基数，为 null。 */
    private Double leadTimeChangePercent;
    /** 进行中工单：查询时刻快照（统一看板分类未结束），历史不可回溯故无环比。 */
    private long inProgressCount;
    /** 一次通过率（%）：区间内 SUCCEEDED 派发中 attempt=0 占比；区间内无成功派发为 null。率类不做环比。 */
    private Double firstPassRate;
    private FailedDispatches failedDispatches;
    /** 返工率（%）：评论返工行或三段式幂等键 attempt>=2 的派发占比；区间内无派发为 null。率类不做环比。 */
    private Double reworkRate;
    private TokenUsage tokenUsage;
    /** 区间内失败/超时派发原因 TOP5（按次数降序）。 */
    private List<FailureReason> failureReasons;

    @Getter
    @Setter
    public static class Created {
        private long count;
        /** 区间内逐日创建数（Asia/Shanghai 自然日），无数据的日期零填充。 */
        private List<DailyCount> daily;
    }

    @Getter
    @Setter
    public static class DailyCount {
        private String date;
        private long count;
    }

    @Getter
    @Setter
    public static class Delivered {
        private long count;
        /** 完成数 / 区间天数，一位小数。 */
        private Double dailyAverage;
        /** 紧邻的前一个等时长区间。 */
        private Period previous;
        /** (本期 - 上期) / 上期 × 100，一位小数；上期为 0 时无可比基数，返回 null。 */
        private Double changePercent;
    }

    @Getter
    @Setter
    public static class Period {
        private String startDate;
        private String endDate;
        private long count;
    }

    /** 失败/超时派发：计数 + 占全部正式交付派发比 + 环比。 */
    @Getter
    @Setter
    public static class FailedDispatches {
        private long count;
        /** 失败数 / 区间内全部正式交付派发数（%），一位小数；区间内无派发为 0。 */
        private Double sharePercent;
        private Period previous;
        /** (本期 - 上期) / 上期 × 100，一位小数；上期为 0 时无可比基数，返回 null。 */
        private Double changePercent;
    }

    /** Token 消耗：区间合计 + 逐日趋势 + 环比。 */
    @Getter
    @Setter
    public static class TokenUsage {
        private long count;
        /** 区间内逐日 Token 消耗（Asia/Shanghai 自然日），无数据的日期零填充。 */
        private List<DailyCount> daily;
        private Period previous;
        private Double changePercent;
    }

    /** 失败原因聚合行：结构化归因优先，error 前 60 字回退；皆无为 UNKNOWN。 */
    @Getter
    @Setter
    public static class FailureReason {
        private String reason;
        private long count;
        /** 该原因次数 / 区间内失败/超时总数（%），一位小数。 */
        private Double sharePercent;
        /** 该原因最近一次发生时间（ISO-8601）。 */
        private String lastTime;
    }
}
