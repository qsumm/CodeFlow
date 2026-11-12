package com.ainative.workitem;

import java.util.Arrays;
import java.util.Optional;

/**
 * Stable event codes persisted in {@code workitem_event}.  The code is used for storage and
 * integration compatibility; the action label is the single source for timeline presentation.
 */
public enum WorkitemEventType {
    CREATE("工作项已创建"),
    STATUS_CHANGE("工作项状态已变更"),
    ASSIGN("交付负责人已变更"),
    EDIT("工作项内容已更新"),
    DELETE("工作项已删除"),
    RESTORE("工作项已从回收站恢复"),
    COMMENT("新增评论"),
    TAG_CHANGE("工作项标签已更新"),
    EXECUTION_CONTROL("执行控制"),
    DELIVERY_CONTROL("交付控制"),

    EXTERNAL_IMPORT("已从外部工作项导入"),
    EXTERNAL_UPDATE("已从外部工作项同步更新"),
    EXTERNAL_BUSINESS_OWNER_CHANGE("外部业务负责人已变更"),
    EXTERNAL_LIFECYCLE_CHANGE("来源工作项生命周期已变更"),
    EXTERNAL_COMMENT_EDIT("外部评论信息已更新"),
    EXTERNAL_COMMENT_AUTHOR_CHANGE("外部评论作者身份已更新"),
    EXTERNAL_COMMENT_DELETE("外部评论已删除");

    private final String actionLabel;

    WorkitemEventType(String actionLabel) {
        this.actionLabel = actionLabel;
    }

    public String code() {
        return name();
    }

    public String actionLabel() {
        return actionLabel;
    }

    public static Optional<WorkitemEventType> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.code().equals(code)).findFirst();
    }
}
