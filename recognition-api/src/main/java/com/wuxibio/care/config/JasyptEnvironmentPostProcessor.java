package com.wuxibio.care.config;

import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class JasyptEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String ENCRYPTED_PREFIX = "ENC(";
    private static final String ENCRYPTED_SUFFIX = ")";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources propertySources = environment.getPropertySources();
        List<String> sourceNames = new ArrayList<>();
        propertySources.forEach(propertySource -> sourceNames.add(propertySource.getName()));

        Supplier<StringEncryptor> encryptorSupplier = new LazyEncryptorSupplier(environment);
        for (String sourceName : sourceNames) {
            PropertySource<?> propertySource = propertySources.get(sourceName);
            if (propertySource == null
                    || propertySource instanceof EncryptablePropertySource
                    || propertySource instanceof EncryptableEnumerablePropertySource
                    || propertySource instanceof PropertySource.StubPropertySource
                    || ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource)) {
                continue;
            }

            PropertySource<?> encryptableSource = propertySource instanceof EnumerablePropertySource<?>
                    ? new EncryptableEnumerablePropertySource((EnumerablePropertySource<?>) propertySource, encryptorSupplier)
                    : new EncryptablePropertySource(propertySource, encryptorSupplier);
            propertySources.replace(sourceName, encryptableSource);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 20;
    }

    private static final class EncryptablePropertySource extends PropertySource<PropertySource<?>> {

        private final Supplier<StringEncryptor> encryptorSupplier;

        private EncryptablePropertySource(PropertySource<?> delegate, Supplier<StringEncryptor> encryptorSupplier) {
            super(delegate.getName(), delegate);
            this.encryptorSupplier = encryptorSupplier;
        }

        @Override
        public Object getProperty(String name) {
            return decryptIfNecessary(name, source.getProperty(name), encryptorSupplier);
        }
    }

    private static final class EncryptableEnumerablePropertySource extends EnumerablePropertySource<PropertySource<?>> {

        private final Supplier<StringEncryptor> encryptorSupplier;

        private EncryptableEnumerablePropertySource(
                EnumerablePropertySource<?> delegate,
                Supplier<StringEncryptor> encryptorSupplier) {
            super(delegate.getName(), delegate);
            this.encryptorSupplier = encryptorSupplier;
        }

        @Override
        public String[] getPropertyNames() {
            return ((EnumerablePropertySource<?>) source).getPropertyNames();
        }

        @Override
        public Object getProperty(String name) {
            return decryptIfNecessary(name, source.getProperty(name), encryptorSupplier);
        }
    }

    private static Object decryptIfNecessary(String propertyName, Object value, Supplier<StringEncryptor> encryptorSupplier) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith(ENCRYPTED_PREFIX) || !trimmed.endsWith(ENCRYPTED_SUFFIX)) {
            return value;
        }

        String encryptedValue = trimmed.substring(ENCRYPTED_PREFIX.length(), trimmed.length() - ENCRYPTED_SUFFIX.length());
        try {
            return encryptorSupplier.get().decrypt(encryptedValue);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to decrypt encrypted property '" + propertyName + "'", ex);
        }
    }

    private static final class LazyEncryptorSupplier implements Supplier<StringEncryptor> {

        private final Environment environment;
        private volatile StringEncryptor encryptor;

        private LazyEncryptorSupplier(Environment environment) {
            this.environment = environment;
        }

        @Override
        public StringEncryptor get() {
            StringEncryptor current = encryptor;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                if (encryptor == null) {
                    encryptor = createEncryptor(environment);
                }
                return encryptor;
            }
        }
    }

    private static StringEncryptor createEncryptor(Environment environment) {
        String password = firstText(
                environment.getProperty("jasypt.encryptor.password"),
                environment.getProperty("JASYPT_ENCRYPTOR_PASSWORD"));
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "Encrypted configuration requires JASYPT_ENCRYPTOR_PASSWORD or jasypt.encryptor.password");
        }

        SimpleStringPBEConfig config = new SimpleStringPBEConfig();
        config.setPassword(password);
        config.setAlgorithm(environment.getProperty(
                "jasypt.encryptor.algorithm",
                "PBEWITHHMACSHA512ANDAES_256"));
        config.setKeyObtentionIterations(environment.getProperty(
                "jasypt.encryptor.key-obtention-iterations",
                "1000"));
        config.setPoolSize(environment.getProperty("jasypt.encryptor.pool-size", "1"));
        config.setSaltGeneratorClassName(environment.getProperty(
                "jasypt.encryptor.salt-generator-classname",
                "org.jasypt.salt.RandomSaltGenerator"));
        config.setIvGeneratorClassName(environment.getProperty(
                "jasypt.encryptor.iv-generator-classname",
                "org.jasypt.iv.RandomIvGenerator"));
        config.setStringOutputType(environment.getProperty("jasypt.encryptor.string-output-type", "base64"));

        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        encryptor.setConfig(config);
        return encryptor;
    }

    private static String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return second;
    }
}
