package com.wuxibio.care.channel;

import java.util.Map;

/**
 * Message channel strategy interface.
 * MVP: EmailChannel, DingTalkChannel
 * Long-term: TeamsChannel
 */
public interface MessageChannel {

    /**
     * Channel type identifier (e.g., "Email", "DingTalk", "Teams")
     */
    String getType();

    /**
     * Send a message to a single recipient.
     *
     * @param recipient recipient identifier (email address, DingTalk userId, etc.)
     * @param subject   message subject/title
     * @param content   message body (HTML for email, text/markdown for others)
     */
    void send(String recipient, String subject, String content);

    /**
     * Send a rendered channel message with optional structured payload metadata.
     */
    default void send(MessageRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Message request is required");
        }
        send(request.recipient(), request.subject(), request.content());
    }

    record MessageRequest(
            String recipient,
            String subject,
            String content,
            String messageType,
            String channelPayloadJson,
            Map<String, String> metadata) {
        public MessageRequest(String recipient, String subject, String content) {
            this(recipient, subject, content, null, null, Map.of());
        }
    }
}
