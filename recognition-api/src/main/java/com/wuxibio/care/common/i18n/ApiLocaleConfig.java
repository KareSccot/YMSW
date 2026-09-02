package com.wuxibio.care.common.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

@Configuration
public class ApiLocaleConfig {

    @Bean
    public LocaleResolver localeResolver() {
        HeaderLocaleResolver resolver = new HeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        resolver.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.US));
        return resolver;
    }

    static class HeaderLocaleResolver extends AcceptHeaderLocaleResolver {
        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            String explicitLanguage = request.getHeader("X-Language");
            if (explicitLanguage != null && !explicitLanguage.isBlank()) {
                return I18nMessageService.normalizeLocale(explicitLanguage);
            }
            return I18nMessageService.normalizeLocale(request.getHeader("Accept-Language"));
        }
    }
}
