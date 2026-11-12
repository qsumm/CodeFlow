package com.ainative.insights;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface DeliveryOverviewDao {

    /** 区间 [start, end) 内创建的工单（全部类型）。 */
    List<DeliveryOverviewService.CreatedWorkitem> listCreatedWorkitems(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间 [start, end) 内创建的工单总数。 */
    long countCreatedWorkitems(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间 [start, end) 内创建的工单分页明细：创建时间降序（最新在前）。 */
    List<DeliveryOverviewService.CreatedWorkitemRow> listCreatedWorkitemsPage(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end,
            @Param("offset") long offset, @Param("limit") int limit);

    /** 区间 [start, end) 内交付完成的工单数；完成时间取最近一次进入 DONE 类节点的状态事件。 */
    long countDeliveredWorkitems(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 当前用户待处理工单总数（未完成/取消且处理人为本人的工单）。 */
    long countPendingWorkitems(@Param("tenantId") long tenantId, @Param("currentUserId") long currentUserId);

    /** 当前用户待处理工单分页：待决策优先，再按优先级升序（0 最高）、更新时间降序。 */
    List<DeliveryOverviewService.PendingWorkitemRow> listPendingWorkitems(
            @Param("tenantId") long tenantId, @Param("currentUserId") long currentUserId,
            @Param("offset") long offset, @Param("limit") int limit);

    /** 区间 [start, end) 内完成工单的 创建→完成事件 平均秒数；区间内无完成返回 null。 */
    Double avgLeadTimeSeconds(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 当前时点进行中工单数（统一看板分类未结束的快照）。 */
    long countWorkitemsInProgress(@Param("tenantId") long tenantId);

    /** 区间 [start, end) 内正式交付派发总数（占比分母，含正式交付围栏口径）。 */
    long countTotalDispatches(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间内 SUCCEEDED 派发数（一次通过率的分母）。 */
    long countSucceededDispatches(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间内 SUCCEEDED 且 attempt=0 的派发数（一次通过率分子）。 */
    long countFirstPassDispatches(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间内 FAILED/TIMEOUT 派发数。 */
    long countFailedDispatches(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间内返工派发数（评论返工行，或三段式幂等键 + attempt>=2）。 */
    long countReworkDispatches(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间内派发 Token 消耗合计（dispatch_ai_usage 明细合计）。 */
    long sumTokens(@Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间内派发 Token 逐条用量（Java 侧按 Asia/Shanghai 自然日分桶）。 */
    List<DeliveryOverviewService.TokenUsageRow> listTokenUsageRows(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);

    /** 区间内失败/超时派发原因 TOP5（结构化归因优先，error 前 60 字回退）。 */
    List<DeliveryOverviewService.FailureReasonRow> listFailureReasons(
            @Param("tenantId") long tenantId, @Param("start") Date start, @Param("end") Date end);
}
