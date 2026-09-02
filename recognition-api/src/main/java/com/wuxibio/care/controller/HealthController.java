package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public R<Map<String, String>> health() {
        return R.ok(Map.of("status", "ok"));
    }
}
