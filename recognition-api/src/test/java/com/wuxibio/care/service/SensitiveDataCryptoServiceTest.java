package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SensitiveDataCryptoServiceTest {

    @Test
    void valueExpressionUsesJwtSecretWithoutHardcodedFallback() {
        Constructor<?> constructor = SensitiveDataCryptoService.class.getConstructors()[0];
        Value value = constructor.getParameters()[0].getAnnotation(Value.class);

        assertEquals("${app.security.data-key:${app.jwt.secret}}", value.value());
        assertFalse(value.value().contains("wuxibio-employee-care"));
        assertFalse(value.value().contains("LOCAL-DEV-ONLY"));
    }

    @Test
    void encryptDecryptRoundTripStillWorksWithProvidedKey() {
        SensitiveDataCryptoService service = new SensitiveDataCryptoService("unit-test-key");

        String encrypted = service.encryptIfNeeded("secret-value");

        assertFalse(encrypted.contains("secret-value"));
        assertEquals("secret-value", service.decryptIfNeeded(encrypted));
    }
}
