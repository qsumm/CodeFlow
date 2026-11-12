/** 交付总览近期统计与当前用户待处理工单（后端 /api/insights/delivery-overview）。 */

export interface DeliveryOverviewDailyCount {
  date: string;
  count: number;
}

export interface DeliveryOverviewPeriod {
  startDate: string;
  endDate: string;
  count: number;
}

export interface DeliveryOverviewCreated {
  count: number;
  daily: DeliveryOverviewDailyCount[];
}

export interface DeliveryOverviewDelivered {
  count: number;
  dailyAverage: number;
  previous: DeliveryOverviewPeriod;
  /** 上期为 0 时无可比基数，为 null。 */
  changePercent: number | null;
}

export interface DeliveryOverview {
  startDate: string;
  endDate: string;
  timezone: string;
  dataThrough: string;
  daysInRange: number;
  created: DeliveryOverviewCreated;
  delivered: DeliveryOverviewDelivered;
  /** 平均交付周期（天，一位小数）；区间内无完成为 null。 */
  leadTimeDays: number | null;
  leadTimeChangePercent: number | null;
  /** 进行中工单：查询时刻快照（未结分类），无环比。 */
  inProgressCount: number;
  /** 一次通过率（%）：SUCCEEDED 且 attempt=0 占比；区间内无成功派发为 null。 */
  firstPassRate: number | null;
  failedDispatches: {
    count: number;
    /** 占全部正式交付派发比（%）；区间内无派发为 0。 */
    sharePercent: number;
    previous: DeliveryOverviewPeriod;
    changePercent: number | null;
  };
  /** 返工率（%）：评论返工或三段式幂等键 attempt>=2 占比；区间内无派发为 null。 */
  reworkRate: number | null;
  tokenUsage: {
    count: number;
    daily: DeliveryOverviewDailyCount[];
    previous: DeliveryOverviewPeriod;
    changePercent: number | null;
  };
  /** 失败/超时派发原因 TOP5（reason 为 UNKNOWN 表示结构化归因与 error 皆缺）。 */
  failureReasons: DeliveryOverviewFailureReason[];
}

export interface DeliveryOverviewFailureReason {
  reason: string;
  count: number;
  sharePercent: number;
  lastTime: string | null;
}

export interface PendingWorkitem {
  id: number;
  title: string;
  statusName: string | null;
  statusCategory: string;
  priority: number;
  /** AWAITING_DECISION / AWAITING_HANDLING / IN_PROGRESS。 */
  reason: string;
  gmtModified: string;
}

export interface PendingWorkitemPage {
  list: PendingWorkitem[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface CreatedWorkitem {
  id: number;
  title: string;
  statusName: string | null;
  /** 当前状态节点分类：INIT / IN_PROGRESS / DONE / CANCELED，用于状态标签着色。 */
  statusCategory: string | null;
  priority: number;
  createdAt: string;
}

export interface CreatedWorkitemPage {
  list: CreatedWorkitem[];
  total: number;
  pageNum: number;
  pageSize: number;
}
