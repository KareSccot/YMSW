package com.wuxibio.care.channel;

import com.wuxibio.care.service.ExternalConnectionService;
import com.wuxibio.care.service.HtmlToImageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DingTalkChannelTest {

    @Test
    void prepareNativeMessage_uploadsLinkPicUrlReferenceToDingTalkMediaId() {
        AtomicInteger uploadCount = new AtomicInteger();
        DingTalkChannel channel = new DingTalkChannel(
                mock(ExternalConnectionService.class),
                mock(HtmlToImageService.class),
                (accessToken, bytes, mediaType, suffix) -> {
                    uploadCount.incrementAndGet();
                    assertThat(accessToken).isEqualTo("fake-token");
                    assertThat(bytes).containsExactly(1, 2, 3);
                    assertThat(mediaType).isEqualTo("image");
                    assertThat(suffix).isEqualTo(".png");
                    return "$mock_media_id";
                });
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("msgtype", "link");
        msg.put("link", new LinkedHashMap<>(Map.of(
                "title", "Title",
                "text", "Body",
                "messageUrl", "https://example.com",
                "picUrl", "data:image/png;base64,AQID")));

        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = ReflectionTestUtils.invokeMethod(
                channel,
                "prepareNativeMessage",
                "fake-token",
                msg);

        assertThat(normalized).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> link = (Map<String, Object>) normalized.get("link");
        assertThat(link.get("picUrl")).isEqualTo("$mock_media_id");
        assertThat(uploadCount).hasValue(1);
    }

    @Test
    void prepareNativeMessage_keepsDingTalkMediaIdLinkPicUrl() {
        DingTalkChannel channel = new DingTalkChannel(
                mock(ExternalConnectionService.class),
                mock(HtmlToImageService.class),
                (accessToken, bytes, mediaType, suffix) -> {
                    throw new AssertionError("media_id should not be uploaded again");
                });
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("msgtype", "link");
        msg.put("link", new LinkedHashMap<>(Map.of(
                "title", "Title",
                "text", "Body",
                "messageUrl", "https://example.com",
                "picUrl", "@lALOACZwe2Rk")));

        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = ReflectionTestUtils.invokeMethod(
                channel,
                "prepareNativeMessage",
                "fake-token",
                msg);

        assertThat(normalized).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> link = (Map<String, Object>) normalized.get("link");
        assertThat(link.get("picUrl")).isEqualTo("@lALOACZwe2Rk");
    }
}
