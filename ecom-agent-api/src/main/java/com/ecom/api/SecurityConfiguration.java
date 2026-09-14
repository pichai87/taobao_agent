package com.ecom.api;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class SecurityConfiguration {
    @Bean UserDetailsService users(@Value("${agent.auth.username}") String user,@Value("${agent.auth.password}") String password) {
        if(password.length()<12) throw new IllegalArgumentException("应用密码至少12字符");
        return new InMemoryUserDetailsManager(User.withUsername(user)
            .password(new BCryptPasswordEncoder().encode(password)).roles("ANALYST").build());
    }
    @Bean BCryptPasswordEncoder passwordEncoder() {return new BCryptPasswordEncoder();}
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth->auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/", "/index.html", "/app.css", "/app.js").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/api/**").hasRole("ANALYST")
                .anyRequest().denyAll())
            .httpBasic(withDefaults())
            // 保留 CSRF；浏览器/脚本先 GET /api/csrf，再携带会话与令牌发送 POST。
            .csrf(withDefaults()).build();
    }
}
