package com.ainative.integration;

import com.ainative.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationCapabilityController {

    @GetMapping("/capabilities")
    public Result<Map<String, Boolean>> capabilities() {
        return Result.ok(Map.of("zentaoEnabled", true));
    }
}
