package com.wuxibio.care.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.R;
import com.wuxibio.care.common.i18n.I18nMessageService;
import com.wuxibio.care.security.JwtAuthFilter;
import com.wuxibio.care.security.ReplayProtectionFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ReplayProtectionFilter replayProtectionFilter;
    private final ObjectMapper objectMapper;
    private final I18nMessageService i18n;

    public SecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            ReplayProtectionFilter replayProtectionFilter,
            ObjectMapper objectMapper,
            I18nMessageService i18n) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.replayProtectionFilter = replayProtectionFilter;
        this.objectMapper = objectMapper;
        this.i18n = i18n;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public FilterRegistrationBean<ReplayProtectionFilter> replayProtectionFilterRegistration(
            ReplayProtectionFilter filter) {
        FilterRegistrationBean<ReplayProtectionFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write(objectMapper.writeValueAsString(R.fail(401, i18n.translate("未登录或登录已过期", req))));
                })
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write(objectMapper.writeValueAsString(R.fail(403, i18n.translate("无权限访问", req))));
                })
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/api/v1/health").permitAll()
                .requestMatchers("/api/v1/templates/images/**").permitAll()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(replayProtectionFilter, JwtAuthFilter.class);
        return http.build();
    }
}
