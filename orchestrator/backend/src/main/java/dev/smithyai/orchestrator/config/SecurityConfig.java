package dev.smithyai.orchestrator.config;

import java.security.SecureRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth ->
            auth.requestMatchers("/webhooks/**")
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
                form.loginProcessingUrl("/api/login")
                    .successHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value()))
                    .failureHandler((req, res, authEx) -> res.setStatus(HttpStatus.UNAUTHORIZED.value()))
                    .permitAll()
            )
            .logout(logout ->
                logout
                    .logoutUrl("/api/logout")
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value()))
            )
            .csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/**", "/api/**"));
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(
        @Value("${ADMIN_PASSWORD_HASH:}") String passwordHash,
        PasswordEncoder encoder
    ) {
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
