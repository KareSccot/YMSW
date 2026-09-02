package com.wuxibio.care.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SensitiveEntitySerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sysUserPasswordIsWriteOnly() throws Exception {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("bcrypt-hash");

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(user));

        assertFalse(json.has("password"));
        assertEquals("plain-pass", objectMapper.readValue(
                "{\"username\":\"admin\",\"password\":\"plain-pass\"}", SysUser.class).getPassword());
    }

    @Test
    void senderMailboxPasswordIsWriteOnly() throws Exception {
        SenderMailbox mailbox = new SenderMailbox();
        mailbox.setId(2L);
        mailbox.setUsername("smtp-user");
        mailbox.setPassword("smtp-secret");

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(mailbox));

        assertFalse(json.has("password"));
        assertEquals("smtp-secret", objectMapper.readValue(
                "{\"username\":\"smtp-user\",\"password\":\"smtp-secret\"}", SenderMailbox.class).getPassword());
    }

    @Test
    void externalConnectionConfigIsWriteOnly() throws Exception {
        ExternalConnection connection = new ExternalConnection();
        connection.setId(3L);
        connection.setType("SMTP");
        connection.setConfig("{\"password\":\"smtp-secret\"}");

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(connection));

        assertFalse(json.has("config"));
        assertEquals("{\"password\":\"smtp-secret\"}", objectMapper.readValue(
                "{\"type\":\"SMTP\",\"config\":\"{\\\"password\\\":\\\"smtp-secret\\\"}\"}",
                ExternalConnection.class).getConfig());
    }
}
