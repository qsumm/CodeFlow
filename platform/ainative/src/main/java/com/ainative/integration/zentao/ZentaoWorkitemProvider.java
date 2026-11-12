package com.ainative.integration.zentao;

import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.provider.ExternalComment;
import com.ainative.integration.provider.ExternalStatusOption;
import com.ainative.integration.provider.ExternalWorkitemDetail;
import com.ainative.integration.provider.ExternalWorkitemProvider;
import com.ainative.integration.provider.ExternalWorkitemSummary;
import com.ainative.integration.provider.PageResult;
import com.ainative.json.JSONArray;
import com.ainative.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 禅道(ZenTao)v1 REST API 的工单 Provider(实测于 ZenTao 18.10,见 docs/17 §2)。
 *
 * <p>对象范围:第一版接入 Bug 与 Story(两者均有产品级列表 + 单详情 + PUT 回写端点;
 * Task 在 v1 缺产品级列表与单详情端点,登记待端点确认后接入)。
 *
 * <p>externalId 约定:{@code bug-<id>} / {@code story-<id>}(禅道各对象 id 独立自增,必须带类型前缀)。
 *
 * <p>状态机约束(实测):非法转换(如 resolved→active 直接 PUT)会被禅道拒绝,
 * 状态回写只发起合法转换;resolved 需要 resolution,默认 fixed。
 */
@Component
public class ZentaoWorkitemProvider implements ExternalWorkitemProvider {

    private static final Logger log = LoggerFactory.getLogger(ZentaoWorkitemProvider.class);

    public static final String PROVIDER = "ZENTAO";
    private static final int PER_PAGE = 100;
    /** 禅道分页 page 参数有上限(官方约 500 页),防御性截断。 */
    private static final int MAX_PAGES = 100;
    private static final String TZ = "Asia/Shanghai";

    private final ZentaoClient client;

    public ZentaoWorkitemProvider(ZentaoClient client) {
        this.client = client;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    // ---------- 查询 ----------

    @Override
    public PageResult<ExternalWorkitemSummary> searchByIds(ExternalPlatformConfig config, String externalProjectId,
                                                           List<String> ids) {
        List<ExternalWorkitemSummary> items = new ArrayList<>();
        if (ids != null) {
            for (String externalId : ids) {
                try {
                    items.add(toSummary(getTyped(config, externalId)));
                } catch (ZentaoClient.ZentaoNotFoundException notFound) {
                    log.warn("zentao workitem missing id={} — skipped", externalId);
                }
            }
        }
        return PageResult.of(items, 1, Math.max(ids == null ? 0 : ids.size(), 1), items.size());
    }

    @Override
    public PageResult<ExternalWorkitemSummary> searchProject(ExternalPlatformConfig config, String externalProjectId,
                                                             Date createdFrom, Date createdTo) {
        List<ExternalWorkitemSummary> collected = new ArrayList<>();
        collectType(config, "bug", externalProjectId, createdFrom, createdTo, collected);
        collectType(config, "story", externalProjectId, createdFrom, createdTo, collected);
        return PageResult.of(collected, 1, PER_PAGE, collected.size());
    }

    @Override
    public PageResult<ExternalWorkitemSummary> searchProjectFirstPage(ExternalPlatformConfig config,
                                                                      String externalProjectId) {
        List<ExternalWorkitemSummary> collected = new ArrayList<>();
        collectType(config, "bug", externalProjectId, null, null, collected);
        collectType(config, "story", externalProjectId, null, null, collected);
        return PageResult.of(collected, 1, PER_PAGE, collected.size());
    }

    @Override
    public ExternalWorkitemDetail getWorkitem(ExternalPlatformConfig config, String externalWorkitemId) {
        return toDetail(getTyped(config, externalWorkitemId), config.baseUrl());
    }

    @Override
    public Map<String, List<ExternalStatusOption>> listOperationalStatuses(ExternalPlatformConfig config, String staffId,
                                                                           List<String> externalWorkitemIds) {
        // 禅道状态是对象级固定枚举(实测 18.10),不随工单变化;按工单对象类型返回合法状态集。
        Map<String, List<ExternalStatusOption>> byWorkitem = new LinkedHashMap<>();
        if (externalWorkitemIds == null) {
            return byWorkitem;
        }
        for (String externalId : externalWorkitemIds) {
            byWorkitem.put(externalId, staticStatuses(externalId));
        }
        return byWorkitem;
    }

    /** 禅道对象类型的合法状态集(实测 18.10 状态机)。 */
    static List<ExternalStatusOption> staticStatuses(String externalId) {
        List<ExternalStatusOption> statuses = new ArrayList<>();
        for (String def : "bug:active:激活|bug:resolved:已解决|bug:closed:已关闭"
                .concat("|story:draft:草稿|story:active:激活|story:changed:变更中|story:closed:已关闭").split("\\|")) {
            String[] parts = def.split(":");
            if (externalId == null || externalId.startsWith(parts[0] + "-")) {
                ExternalStatusOption option = new ExternalStatusOption();
                option.setExternalId(parts[0] + "-" + parts[1]);
                option.setName(parts[2]);
                statuses.add(option);
            }
        }
        return statuses;
    }

    // ---------- 回写 ----------

    @Override
    public List<ExternalComment> listComments(ExternalPlatformConfig config, List<String> externalWorkitemIds) {
        List<ExternalComment> comments = new ArrayList<>();
        if (externalWorkitemIds == null) {
            return comments;
        }
        for (String externalId : externalWorkitemIds) {
            try {
                JSONObject detail = getTyped(config, externalId);
                comments.addAll(extractActionsAsComments(externalId, detail));
            } catch (ZentaoClient.ZentaoNotFoundException notFound) {
                log.warn("zentao comments: workitem missing id={}", externalId);
            }
        }
        return comments;
    }

    @Override
    public ExternalComment createComment(ExternalPlatformConfig config, String externalWorkitemId, String staffId,
                                         String contentMd) {
        TypedRef ref = parse(externalWorkitemId);
        JSONObject body = new JSONObject();
        if (ref.type().equals("story")) {
            // 禅道 story 的 v1 PUT 白名单(实测 18.10):title/product/.../keywords/stage 等,
            // 不含 comment 与 spec——备注被静默丢弃。story 备注落 keywords(白名单内,实测可写);
            // 逗号会被 keywords 解析拆分,先归一为顿号并截断。
            body.put("keywords", storyCommentKeywords(contentMd));
        } else {
            // bug 的 v1 PUT 白名单同样不含 comment(docs/17 §2 该行按 v1 实测修正):
            // 编辑 action 会记录但备注文本被丢弃。bug 备注追加到 steps(白名单内,实测可写)。
            JSONObject detail = getTyped(config, externalWorkitemId);
            String steps = detail.getString("steps") == null ? "" : detail.getString("steps");
            body.put("steps", steps + toZentaoStepsHtml(contentMd));
        }
        JSONObject resp = client.putJson(config.baseUrl(), config.clientKey(), config.accessSecret(),
                path(ref), body);
        ExternalComment comment = new ExternalComment();
        comment.setExternalId("comment-" + externalWorkitemId + "-" + System.currentTimeMillis());
        comment.setExternalWorkitemId(externalWorkitemId);
        comment.setAuthorStaffId(staffId);
        comment.setContentMd(contentMd);
        return comment;
    }

    @Override
    public void updateStatus(ExternalPlatformConfig config, String externalWorkitemId, String staffId, String statusName) {
        TypedRef ref = parse(externalWorkitemId);
        JSONObject body = new JSONObject();
        body.put("status", ref.type().equals("bug") ? bugWriteStatus(statusName) : storyWriteStatus(statusName));
        if ("resolved".equals(body.getString("status"))) {
            body.put("resolution", "fixed"); // 禅道 resolved 必填 resolution(实测),默认 fixed
            body.put("resolvedBuild", "trunk");
        }
        client.putJson(config.baseUrl(), config.clientKey(), config.accessSecret(), path(ref), body);
    }

    @Override
    public void updateContent(ExternalPlatformConfig config, String externalWorkitemId, String staffId, String title,
                              String contentMd) {
        TypedRef ref = parse(externalWorkitemId);
        JSONObject body = new JSONObject();
        if (title != null) {
            body.put("title", title);
        }
        if (contentMd != null) {
            // Bug 正文字段是 steps,Story 正文字段是 spec(实测 18.10;写错字段禅道静默忽略)
            body.put(ref.type().equals("story") ? "spec" : "steps", toZentaoStepsHtml(contentMd));
        }
        client.putJson(config.baseUrl(), config.clientKey(), config.accessSecret(), path(ref), body);
    }

    // ---------- 内部:类型路由与端点 ----------

    record TypedRef(String type, String id) { }

    static TypedRef parse(String externalId) {
        int sep = externalId.indexOf('-');
        if (sep <= 0) {
            throw new IllegalArgumentException("invalid zentao external id: " + externalId);
        }
        String type = externalId.substring(0, sep);
        if (!type.equals("bug") && !type.equals("story")) {
            throw new IllegalArgumentException("unsupported zentao object type: " + type);
        }
        return new TypedRef(type, externalId.substring(sep + 1));
    }

    private static String path(TypedRef ref) {
        return "/api.php/v1/" + plural(ref.type()) + "/" + ref.id();
    }

    private static String listPath(String type, String productId, int page) {
        return "/api.php/v1/products/" + productId + "/" + plural(type) + "?page=" + page + "&limit=" + PER_PAGE;
    }

    /** 禅道 v1 端点复数(实测):bugs / stories——story 不是 storys。 */
    private static String plural(String type) {
        return "story".equals(type) ? "stories" : type + "s";
    }

    private JSONObject getTyped(ExternalPlatformConfig config, String externalId) {
        TypedRef ref = parse(externalId);
        return client.getJson(config.baseUrl(), config.clientKey(), config.accessSecret(), path(ref));
    }

    private void collectType(ExternalPlatformConfig config, String type, String productId, Date createdFrom,
                             Date createdTo, List<ExternalWorkitemSummary> collected) {
        for (int page = 1; page <= MAX_PAGES; page++) {
            JSONObject resp = client.getJson(config.baseUrl(), config.clientKey(), config.accessSecret(),
                    listPath(type, productId, page));
            JSONArray batch = resp.getJSONArray(plural(type));
            if (batch == null || batch.isEmpty()) {
                return;
            }
            for (int i = 0; i < batch.size(); i++) {
                JSONObject item = batch.getJSONObject(i);
                Date edited = firstDate(item.getString("lastEditedDate"), item.getString("openedDate"));
                if (createdFrom != null && (edited == null || edited.before(createdFrom))) {
                    continue;
                }
                if (createdTo != null && edited != null && edited.after(createdTo)) {
                    continue;
                }
                collected.add(toSummary(item, type));
            }
            int total = resp.getIntValue("total");
            if (page * PER_PAGE >= total) {
                return;
            }
        }
        log.warn("zentao {} scan hit page cap for product={}, remaining items skipped", type, productId);
    }

    // ---------- 映射 ----------

    private ExternalWorkitemSummary toSummary(JSONObject bugOrStory) {
        return toSummary(bugOrStory, bugOrStory.containsKey("severity") ? "bug" : "story");
    }

    private ExternalWorkitemSummary toSummary(JSONObject item, String type) {
        ExternalWorkitemSummary summary = new ExternalWorkitemSummary();
        String id = item.getString("id");
        summary.setExternalId(type + "-" + id);
        summary.setExternalProjectId(item.getString("product"));
        summary.setExternalIssueTypeId(type);
        summary.setWorkType("bug".equals(type) ? "BUG" : "REQ");
        summary.setTitle(item.getString("title"));
        String status = item.getString("status");
        summary.setStatusId(status);
        summary.setStatusName(zentaoStatusLabel(type, status));
        summary.setUpdatedAt(firstDate(item.getString("lastEditedDate"), item.getString("openedDate")));
        summary.setRawJson(item.toJSONString());
        return summary;
    }

    private ExternalWorkitemDetail toDetail(JSONObject item, String baseUrl) {
        String type = item.containsKey("severity") ? "bug" : "story";
        ExternalWorkitemDetail detail = new ExternalWorkitemDetail();
        String id = item.getString("id");
        detail.setExternalId(type + "-" + id);
        detail.setExternalProjectId(item.getString("product"));
        detail.setExternalIssueTypeId(type);
        detail.setWorkType("bug".equals(type) ? "BUG" : "REQ");
        detail.setTitle(item.getString("title"));
        detail.setContentMd(htmlToText(contentField(item, type)));
        String status = item.getString("status");
        detail.setStatusId(status);
        detail.setStatusName(zentaoStatusLabel(type, status));
        detail.setPriority(parseInt(item.getString("pri"), 3));
        detail.setAssigneeStaffId(accountOf(item.getJSONObject("assignedTo")));
        detail.setAuthorStaffId(accountOf(item.getJSONObject("openedBy")));
        detail.setExternalUrl(webUrl(baseUrl, type, id));
        detail.setCreatedAt(firstDate(item.getString("openedDate")));
        detail.setUpdatedAt(firstDate(item.getString("lastEditedDate"), item.getString("openedDate")));
        return detail;
    }

    private List<ExternalComment> extractActionsAsComments(String externalId, JSONObject detail) {
        List<ExternalComment> comments = new ArrayList<>();
        JSONArray actions = detail.getJSONArray("actions");
        if (actions == null) {
            return comments;
        }
        for (int i = 0; i < actions.size(); i++) {
            JSONObject action = actions.getJSONObject(i);
            String comment = action.getString("comment");
            boolean isCommentAction = "comment".equals(action.getString("action"));
            if ((comment == null || comment.isBlank()) && !isCommentAction) {
                continue;
            }
            ExternalComment comment1 = new ExternalComment();
            comment1.setExternalId("comment-" + externalId + "-" + i);
            comment1.setExternalWorkitemId(externalId);
            comment1.setAuthorStaffId(accountOf(action.getJSONObject("actor")));
            comment1.setAuthorName(nameOf(action.getJSONObject("actor")));
            comment1.setContentMd(htmlToText(comment == null ? "" : comment));
            comment1.setCreatedAt(firstDate(action.getString("date")));
            comments.add(comment1);
        }
        return comments;
    }


    /**
     * story 备注经 keywords 字段回写:逗号归一、按 UTF-8 字节截断。
     * 禅道(PHP strlen)按<b>字节</b>校验 keywords ≤255——中文每字 3 字节,
     * 按字符截断会超限(DEF-030:多轮交付评论回写持续 400)。
     */
    static String storyCommentKeywords(String contentMd) {
        String text = contentMd == null ? "" : contentMd
                .replace(',', '、').replace(',', '、')
                .replaceAll("\\s+", " ")
                .trim();
        String prefix = "ainative-交付:";
        int maxBytes = 255 - utf8Length(prefix);
        while (utf8Length(text) > maxBytes) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return prefix + text;
    }

    private static int utf8Length(String s) {
        return s == null ? 0 : s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** 禅道回写状态名映射(bug):本地语义/节点名 → 禅道合法状态。 */
    static String bugWriteStatus(String target) {
        String lower = target == null ? "" : target.toLowerCase();
        if (lower.contains("解决") || lower.contains("resolved") || lower.contains("fixed")) {
            return "resolved";
        }
        if (lower.contains("关闭") || lower.contains("完成") || lower.contains("done")
                || lower.contains("closed") || lower.contains("completed")) {
            return "closed";
        }
        return "active";
    }

    /** 禅道回写状态名映射(story):本地语义/节点名 → 禅道合法状态。 */
    static String storyWriteStatus(String target) {
        String lower = target == null ? "" : target.toLowerCase();
        if (lower.contains("变更") || lower.contains("changed")) {
            return "changed";
        }
        if (lower.contains("关闭") || lower.contains("完成") || lower.contains("done")
                || lower.contains("closed") || lower.contains("completed")) {
            return "closed";
        }
        return "active";
    }

    /**
     * 禅道对象正文字段(实测 18.10):Bug 正文在 {@code steps},需求正文在 {@code spec}
     * (Story 无 steps 字段,只有 spec 需求描述 + verify 验收标准)。
     */
    static String contentField(JSONObject item, String type) {
        if ("story".equals(type)) {
            String spec = item.getString("spec");
            if (spec != null && !spec.isBlank()) {
                return spec;
            }
            return item.getString("verify");
        }
        String steps = item.getString("steps");
        if (steps != null && !steps.isBlank()) {
            return steps;
        }
        return item.getString("spec");
    }

    static String zentaoStatusLabel(String type, String status) {
        Map<String, String> labels = Map.ofEntries(
                Map.entry("bug-active", "激活"),
                Map.entry("bug-resolved", "已解决"),
                Map.entry("bug-closed", "已关闭"),
                Map.entry("story-draft", "草稿"),
                Map.entry("story-active", "激活"),
                Map.entry("story-changed", "变更中"),
                Map.entry("story-closed", "已关闭"));
        return labels.getOrDefault(type + "-" + status, status == null ? "" : status);
    }

    /** 禅道 Web 详情页地址:{baseUrl}/{type}-view-{id}.html(实测 18.10)。 */
    static String webUrl(String baseUrl, String type, String id) {
        String root = baseUrl == null ? "" : baseUrl.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + "/" + type + "-view-" + id + ".html";
    }

    private static String accountOf(JSONObject principal) {
        return principal == null ? null : principal.getString("account");
    }

    private static String nameOf(JSONObject principal) {
        return principal == null ? null : principal.getString("realname");
    }

    private static Integer parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.valueOf(value.trim());
        } catch (NumberFormatException notNumeric) {
            return fallback;
        }
    }

    private static Date firstDate(String... candidates) {
        for (String candidate : candidates) {
            Date parsed = parseZentaoDate(candidate);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Date parseZentaoDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // 禅道 18.10 实际返回 ISO UTC("...Z")与本地("yyyy-MM-dd HH:mm:ss")两种;XXX 不吃 "Z" 后缀
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern);
            format.setTimeZone(TimeZone.getTimeZone("yyyy-MM-dd'T'HH:mm:ss'Z'".equals(pattern) ? "UTC" : TZ));
            format.setLenient(false);
            try {
                return format.parse(value.trim());
            } catch (ParseException tryNext) {
                // try next pattern
            }
        }
        log.warn("zentao date unparseable: {}", value);
        return null;
    }

    /** 禅道 steps 为 HTML;导入侧按纯文本处理,剥离标签并保留段落结构。 */
    static String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        return html
                .replace("<br/>", "\n").replace("<br />", "\n").replace("<br>", "\n")
                .replace("</p>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#039;", "'")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /** 回写内容反向转禅道 steps 的 HTML(p 段落)。 */
    static String toZentaoStepsHtml(String contentMd) {
        if (contentMd == null) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        for (String line : contentMd.replace("\r\n", "\n").split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            html.append("<p>").append(escaped).append("</p>");
        }
        return html.toString();
    }
}
