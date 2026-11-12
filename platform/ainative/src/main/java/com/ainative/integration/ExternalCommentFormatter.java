package com.ainative.integration;

import org.springframework.stereotype.Component;

@Component
public class ExternalCommentFormatter {

    public String format(String identityName, String sourceText, String body) {
        String name = blank(identityName) ? "系统" : identityName.trim();
        String source = blank(sourceText) ? "CodeFlow 系统" : sourceText.trim();
        String content = body == null ? "" : body.trim();
        return "CodeFlow · " + name + "\n"
                + "来源：" + source + "\n\n"
                + content;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
