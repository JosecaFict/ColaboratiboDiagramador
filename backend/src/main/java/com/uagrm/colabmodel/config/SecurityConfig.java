package com.uagrm.colabmodel.config;

import com.uagrm.colabmodel.iam.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;

import java.util.ArrayList;
import java.util.List;

/**
 * FASE 1: JWT + roles OWNER/EDITOR/VIEWER.
 *
 * Publico: registro/login (EMITEN el token, asi que no pueden requerirlo) y el
 * handshake de WebSocket (la autenticacion real de la sesion WS pasa por
 * StompAuthChannelInterceptor en el CONNECT, no aqui a nivel HTTP). Todo lo
 * demas necesita un JWT valido.
 *
 * `/api/v1/dev/**` (el atajo que emite un token sin contrasena) solo se abre
 * con el perfil `dev`, y el controlador que lo atiende solo existe con ese
 * perfil (ADR-026): dos candados independientes, para que olvidarse de uno no
 * lo deje abierto en produccion.
 *
 * El escenario de calidad EC-11 exige que un VIEWER no pueda escribir ni
 * siquiera llamando la API a mano: eso NO se decide aqui (este filtro solo
 * autentica quien sos), se decide en AccesoAProyecto, que consultan
 * DiagramCommandBus y los servicios de proyectos.
 */
@Configuration
public class SecurityConfig {

    static final String PERFIL_DE_DESARROLLO = "dev";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                           Environment entorno) throws Exception {
        List<String> publicas = new ArrayList<>(List.of("/api/v1/auth/**", "/ws/**",
                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"));
        if (entorno.acceptsProfiles(Profiles.of(PERFIL_DE_DESARROLLO))) {
            publicas.add("/api/v1/dev/**");
        }

        RespuestasDeSeguridad respuestas = new RespuestasDeSeguridad();
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Cuando un controlador revienta, el contenedor reenvia el pedido a /error
                // para armar la respuesta. Si la seguridad lo bloquea, un 500 llega
                // convertido en un 401 --y la interfaz, que cierra la sesion ante un 401,
                // sacaria al usuario por un error del servidor--.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(publicas.toArray(String[]::new)).permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(respuestas)
                .accessDeniedHandler(respuestas))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
