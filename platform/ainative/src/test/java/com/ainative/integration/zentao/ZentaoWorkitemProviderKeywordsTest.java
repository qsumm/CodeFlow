package com.ainative.integration.zentao;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DEF-030:keywords 载体必须按 UTF-8 字节(禅道 PHP strlen)而非字符截断。 */
class ZentaoWorkitemProviderKeywordsTest {

    private static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void longChineseContentIsTruncatedToByteLimit() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 400) {
            sb.append("交付结论:构建与测试全部通过,评审问题已逐条修复;");
        }
        String keywords = ZentaoWorkitemProvider.storyCommentKeywords(sb.toString());
        assertTrue(utf8Bytes(keywords) <= 255,
                () -> "keywords 超字节限: " + utf8Bytes(keywords));
        assertTrue(keywords.startsWith("ainative-交付:"));
    }

    @Test
    void shortContentKeepsPrefixAndText() {
        String keywords = ZentaoWorkitemProvider.storyCommentKeywords("构建通过, 测试通过");
        assertEquals("ainative-交付:构建通过、 测试通过", keywords);
        assertTrue(utf8Bytes(keywords) <= 255);
    }

    @Test
    void asciiContentStillRespectsLimit() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 400) {
            sb.append("ok,");
        }
        String keywords = ZentaoWorkitemProvider.storyCommentKeywords(sb.toString());
        assertTrue(utf8Bytes(keywords) <= 255);
    }

    @Test
    void nullAndBlankAreSafe() {
        assertEquals("ainative-交付:", ZentaoWorkitemProvider.storyCommentKeywords(null));
        assertEquals("ainative-交付:", ZentaoWorkitemProvider.storyCommentKeywords("  "));
    }
}
