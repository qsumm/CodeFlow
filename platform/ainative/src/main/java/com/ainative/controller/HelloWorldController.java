package com.ainative.controller;

import com.ainative.common.i18n.SystemMessages;

import com.ainative.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloWorldController {

    @GetMapping("/hello")
    public Result<String> hello() {
        return Result.ok(SystemMessages.render("hello.greeting", null, "Hello from AINative!"));
    }
}
