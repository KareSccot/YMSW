package com.wuxibio.care.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UatApplicationConfigurationTest {

    @Test
    void sensitiveValuesUseEnvironmentVariablesWithoutPlaintextDefaults() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-uat.yml"));
        Properties properties = yaml.getObject();

        assertNotNull(properties);
        assertEquals("${UAT_MYSQL_USERNAME:${MYSQL_USERNAME:}}",
                properties.getProperty("spring.datasource.username"));
        assertEquals("${UAT_MYSQL_PASSWORD:${MYSQL_PASSWORD:}}",
                properties.getProperty("spring.datasource.password"));
        assertEquals("${UAT_JWT_SECRET:${JWT_SECRET:}}",
                properties.getProperty("app.jwt.secret"));
        assertEquals("${UAT_DATA_KEY:${DATA_KEY:}}",
                properties.getProperty("app.security.data-key"));
    }
}
