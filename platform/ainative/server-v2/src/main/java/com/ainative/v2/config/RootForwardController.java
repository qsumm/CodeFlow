package com.ainative.v2.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 根路径直接输出 SPA 入口(SPA 回退 resolver 不覆盖裸 "/" 时的兜底)。 */
@RestController
public class RootForwardController {
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public Resource root() throws Exception {
        return new ClassPathResource("/frontend/index.html");
    }
}
