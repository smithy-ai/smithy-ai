package dev.smithyai.orchestrator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth ->
                auth
                    .requestMatchers("/webhooks/**")
                    .permitAll()
                    .requestMatchers("/api/health")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll()
            )
            .exceptionHandling(ex ->
                ex.authenticationEntryPoint((req, res, authEx) -> res.setStatus(HttpStatus.UNAUTHORIZED.value()))
            )
            .formLogin(form ->
                form
                    .loginProcessingUrl("/api/login")
                    .successHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value()))
                    .failureHandler((req, res, authEx) -> res.setStatus(HttpStatus.UNAUTHORIZED.value()))
                    .permitAll()
            )
            .logout(logout ->
                logout
                    .logoutUrl("/api/logout")
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value()))
            )
            // The dashboard reads the token from the XSRF-TOKEN cookie and echoes
            // it in X-XSRF-TOKEN. Webhooks are exempt: providers cannot fetch a
            // token, and their signature is the check.
            .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/webhooks/**"))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            // Written before the request is handled rather than when the response
            // commits: static resources are sent by the container in a way that
            // skips the commit hook, which left the dashboard page without them.
            .headers(headers ->
                headers.addObjectPostProcessor(
                    new ObjectPostProcessor<HeaderWriterFilter>() {
                        @Override
                        public <O extends HeaderWriterFilter> O postProcess(O filter) {
                            filter.setShouldWriteHeadersEagerly(true);
                            return filter;
                        }
                    }
                )
            );
        return http.build();
    }

    /**
     * The token is loaded lazily, so without this the cookie is only set once
     * something asks for it — and the login form, the dashboard's first POST,
     * never does.
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(request, response);
        }
    }

    @Bean
    public UserDetailsService userDetailsService(
        AuthConfig authConfig,
        ConfigLoader configLoader,
        PasswordEncoder encoder
    ) {
        SecretRef passwordHashRef = authConfig.admin() == null ? null : authConfig.admin().passwordHash();
        String passwordHash = configLoader.resolveSecret(passwordHashRef, "auth.admin.passwordHash");
        String effectiveHash;
        if (passwordHash == null || passwordHash.isBlank()) {
            String generated = generatePassword(12);
            effectiveHash = encoder.encode(generated);
            System.out.println();
            System.out.println("============================================================");
            System.out.println("  Smithy-AI Admin Password: " + generated);
            System.out.println("============================================================");
            System.out.println();
        } else {
            effectiveHash = passwordHash;
        }

        var admin = User.builder().username("admin").password(effectiveHash).roles("ADMIN").build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static String generatePassword(int length) {
        var random = new SecureRandom();
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
