package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.service.DingTalkLandingPageService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/message-pages")
public class DingTalkLandingPageController {

    private final DingTalkLandingPageService landingPageService;

    public DingTalkLandingPageController(DingTalkLandingPageService landingPageService) {
        this.landingPageService = landingPageService;
    }

    @GetMapping("/{accessToken}")
    public ResponseEntity<R<DingTalkLandingPageService.PublicLandingPage>> getPage(
            @PathVariable String accessToken) {
        DingTalkLandingPageService.PublicLandingPage page = landingPageService.getPublicPage(accessToken);
        if (page == null) {
            return ResponseEntity.status(404).body(R.fail(404, "页面不存在或已失效"));
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(R.ok(page));
    }
}
