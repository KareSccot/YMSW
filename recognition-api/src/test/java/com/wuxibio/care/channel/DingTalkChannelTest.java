package com.wuxibio.care.channel;

import com.wuxibio.care.service.ExternalConnectionService;
import com.wuxibio.care.service.HtmlToImageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DingTalkChannelTest {

    @Test
    void send_requiresExplicitTransportRouting() {
        DingTalkChannel channel = new DingTalkChannel(
                mock(ExternalConnectionService.class),
                mock(HtmlToImageService.class),
                (accessToken, bytes, mediaType, suffix) -> "$unused");

        assertThatThrownBy(() -> channel.send(new MessageChannel.MessageRequest(
                "user1", "Title", "Body", "text", null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少明确的发送方式");
    }

    @Test
    void buildServiceAccountRequest_mapsMaintainedMessageTypes() {
        DingTalkChannel channel = new DingTalkChannel(
                mock(ExternalConnectionService.class),
                mock(HtmlToImageService.class),
                (accessToken, bytes, mediaType, suffix) -> "$unused");

        Map<String, Object> textMsg = Map.of("text", Map.of("content", "hello"));
        Map<String, Object> markdownMsg = Map.of("markdown", Map.of("title", "Title", "text", "### Hello"));
        Map<String, Object> imageMsg = Map.of("image", Map.of("media_id", "$image"));
        Map<String, Object> linkMsg = Map.of("link", Map.of(
                "title", "Link", "text", "Summary", "messageUrl", "https://example.com", "picUrl", "$cover"));
        Map<String, Object> actionCardMsg = Map.of("action_card", Map.of(
                "title", "Action", "markdown", "### Act", "btn_orientation", "0",
                "btn_json_list", List.of(Map.of("title", "Open", "action_url", "https://example.com"))));

        Map<String, Object> textBody = invokeServiceRequest(channel, "text", textMsg);
        Map<String, Object> markdownBody = invokeServiceRequest(channel, "markdown", markdownMsg);
        Map<String, Object> imageBody = invokeServiceRequest(channel, "image", imageMsg);
        Map<String, Object> linkBody = invokeServiceRequest(channel, "link", linkMsg);
        Map<String, Object> actionCardBody = invokeServiceRequest(channel, "action_card", actionCardMsg);

        assertThat(textBody).containsEntry("unionid", "service-union")
                .containsEntry("userid_list", List.of("user1"))
                .containsEntry("is_to_all", false)
                .containsEntry("msg_type", "text")
                .containsEntry("text_content", "hello")
                .containsEntry("uuid", "uuid-1");
        assertThat(markdownBody.get("msg_body")).isEqualTo(Map.of(
                "markdown", Map.of("title", "Title", "text", "### Hello")));
        assertThat(imageBody).containsEntry("media_id", "$image");
        assertThat(linkBody.get("msg_body")).isEqualTo(Map.of("link", Map.of(
                "title", "Link",
                "summary", "Summary",
                "link_url", "https://example.com",
                "open_type", 1,
                "cover_image_media_id", "$cover")));
        assertThat(actionCardBody.get("msg_body")).isEqualTo(Map.of("action_card", Map.of(
                "title", "Action",
                "markdown", "### Act",
                "btn_orientation", "0",
                "button_list", List.of(Map.of("title", "Open", "action_url", "https://example.com")))));
    }

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeServiceRequest(
            DingTalkChannel channel,
            String messageType,
            Map<String, Object> msg) {
        return ReflectionTestUtils.invokeMethod(
                channel,
                "buildServiceAccountRequest",
                "service-union",
                "user1",
                messageType,
                msg,
                "uuid-1");
    }
}
