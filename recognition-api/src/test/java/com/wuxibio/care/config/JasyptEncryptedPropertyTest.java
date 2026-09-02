package com.wuxibio.care.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JasyptEncryptedPropertyTest {

    private static final String ENCRYPTED_PASSWORD =
            "ENC(Mzu1Mj8zgzrXNo81HkaWSBeei2i3CI5+yRjV3vLvs3XSlhcTfbJyBQmyuqEpVDlDye+Ksp+0Ob5+0+kxP+9bXA==)";

    @Test
    void decryptsEncryptedDataSourcePassword() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "jasypt.encryptor.password", "test-jasypt-key",
                "spring.datasource.password", ENCRYPTED_PASSWORD)));

        new JasyptEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("rotated-db-password");
    }

    @Test
    void failsFastWhenEncryptedValueHasNoDecryptKey() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.datasource.password", ENCRYPTED_PASSWORD)));

        new JasyptEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThatThrownBy(() -> environment.getProperty("spring.datasource.password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt encrypted property 'spring.datasource.password'");
    }

    @Test
    void preservesSpringBootAttachedConfigurationPropertySource() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        ConfigurationPropertySources.attach(environment);
        PropertySource<?> attachedSource = environment.getPropertySources().iterator().next();

        assertThat(ConfigurationPropertySources.isAttachedConfigurationPropertySource(attachedSource)).isTrue();

        new JasyptEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().get(attachedSource.getName())).isSameAs(attachedSource);
        assertThat(ConfigurationPropertySources.createPropertyResolver(environment.getPropertySources())
                .getProperty("spring.profiles.active")).isNull();
    }
}
