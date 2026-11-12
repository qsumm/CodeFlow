package com.ainative.integration.feishu;

import com.ainative.im.ImRouteSnapshot;

public record FeishuSourceContext(Long bindingId, String appId, String chatId, String messageId,
                                  String senderOpenId, String threadId, ImRouteSnapshot route) {
    public FeishuSourceContext(Long bindingId, String appId, String chatId, String messageId,
                               String senderOpenId, String threadId) {
        this(bindingId, appId, chatId, messageId, senderOpenId, threadId, null);
    }

    public String externalId() { return "FEISHU:" + bindingId + ":" + messageId; }
    public String conversationId() {
        return bindingId + ":" + chatId + (threadId == null || threadId.isBlank() ? "" : ":" + threadId);
    }
}
