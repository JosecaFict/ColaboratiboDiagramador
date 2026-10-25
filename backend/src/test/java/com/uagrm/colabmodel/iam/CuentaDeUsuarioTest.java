package com.uagrm.colabmodel.iam;

import com.uagrm.colabmodel.iam.PerfilController.CambioDeContrasena;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La cuenta propia (ADR-026): registrarse, entrar, ver y cambiar el nombre, y
 * cambiar la contrasena.
 *
 * Usa el BCrypt de verdad: lo que se afirma es "entra con la nueva y no con la
 * vieja", y eso no se puede probar con un codificador de mentira.
 */
class CuentaDeUsuarioTest {

    private static final String SECRETO = "una-clave-de-prueba-de-al-menos-32-bytes-para-hs256";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();
    private AppUserRepository users;
    private AuthController auth;
    private PerfilController perfil;
    private AppUser ana;
    private Principal comoAna;

    @BeforeEach
    void prepararUnaCuenta() {
        users = mock(AppUserRepository.class);
        auth = new AuthController(users, encoder, new JwtService(SECRETO, 60));
        perfil = new PerfilController(users, encoder);

        ana = new AppUser();
        ana.setId(UUID.randomUUID());
        ana.setEmail("ana@uagrm.edu.bo");
        ana.setFullName("Ana Pérez");
        ana.setPasswordHash(encoder.encode("contrasena-vieja"));
        comoAna = () -> ana.getId().toString();

        when(users.findById(ana.getId())).thenReturn(Optional.of(ana));
        when(users.findByEmailIgnoreCase(anyString())).thenAnswer(inv ->
                ana.getEmail().equalsIgnoreCase(inv.getArgument(0)) ? Optional.of(ana) : Optional.empty());
        // Como JPA: al guardar una cuenta nueva le asigna el id.
        when(users.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID());
            return u;
        });
    }

    // ------------------------------------------------------------ contrasena

    @Test
    void cambiar_la_contrasena_exige_la_actual_y_no_cambia_nada_si_no_coincide() {
        String hashAntes = ana.getPasswordHash();

        assertThatThrownBy(() -> perfil.cambiarContrasena(new CambioDeContrasena("no-es-esta", "contrasena-nueva"), comoAna))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    // 400 y NO 401: un 401 con token es "tu sesion murio" y la interfaz cierra la sesion.
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("contraseña actual");
                });

        assertThat(ana.getPasswordHash()).isEqualTo(hashAntes);
        assertThat(iniciarSesion("contrasena-vieja").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void con_la_actual_correcta_entra_con_la_nueva_y_no_con_la_vieja() {
        ResponseEntity<Void> r = perfil.cambiarContrasena(new CambioDeContrasena("contrasena-vieja", "contrasena-nueva"), comoAna);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(iniciarSesion("contrasena-nueva").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(iniciarSesion("contrasena-vieja").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Lo guardado es un hash: la contrasena nueva no queda en claro.
        assertThat(ana.getPasswordHash()).startsWith("$2").doesNotContain("contrasena-nueva");
    }

    @Test
    void la_contrasena_nueva_tiene_minimo_8_caracteres_y_el_mensaje_esta_en_espanol() {
        var cortas = validador.validate(new CambioDeContrasena("contrasena-vieja", "corta"));

        assertThat(cortas).extracting(v -> v.getMessage())
                .containsExactly("La contraseña nueva debe tener al menos 8 caracteres");
        assertThat(validador.validate(new CambioDeContrasena("contrasena-vieja", "ocho-car"))).isEmpty();
    }

    @Test
    void faltan_la_actual_o_la_nueva_da_mensajes_en_espanol() {
        var faltantes = validador.validate(new CambioDeContrasena("", ""));

        assertThat(faltantes).extracting(v -> v.getMessage())
                .contains("Escribí tu contraseña actual", "Escribí la contraseña nueva");
    }

    @Test
    void una_cuenta_demo_con_hash_noop_no_puede_entrar_ni_cambiar_la_contrasena() {
        // Las cuentas que crea /dev/bootstrap tienen "{noop}demo": BCrypt nunca lo acepta.
        AppUser demo = new AppUser();
        demo.setId(UUID.randomUUID());
        demo.setEmail("demo@uagrm.edu.bo");
        demo.setFullName("Usuario Demo");
        demo.setPasswordHash("{noop}demo");
        when(users.findByEmailIgnoreCase("demo@uagrm.edu.bo")).thenReturn(Optional.of(demo));
        when(users.findById(demo.getId())).thenReturn(Optional.of(demo));

        var login = auth.iniciarSesion(new AuthController.LoginRequest("demo@uagrm.edu.bo", "demo"));

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> perfil.cambiarContrasena(new CambioDeContrasena("demo", "otra-larga-1"),
                () -> demo.getId().toString())).isInstanceOf(ResponseStatusException.class);
    }

    // ------------------------------------------------------------ nombre / yo

    @Test
    void yo_devuelve_mis_datos_sin_el_hash() {
        PerfilController.Perfil p = perfil.yo(comoAna);

        assertThat(p.userId()).isEqualTo(ana.getId());
        assertThat(p.email()).isEqualTo("ana@uagrm.edu.bo");
        assertThat(p.fullName()).isEqualTo("Ana Pérez");
    }

    @Test
    void cambiar_el_nombre_lo_guarda_sin_espacios_en_los_bordes() {
        PerfilController.Perfil p = perfil.cambiarNombre(new PerfilController.CambioDeNombre("  Ana María  "), comoAna);

        assertThat(p.fullName()).isEqualTo("Ana María");
        assertThat(ana.getFullName()).isEqualTo("Ana María");
    }

    @Test
    void un_nombre_vacio_o_demasiado_largo_se_rechaza_con_mensaje_en_espanol() {
        assertThat(validador.validate(new PerfilController.CambioDeNombre("   ")))
                .extracting(v -> v.getMessage()).containsExactly("El nombre es obligatorio");
        assertThat(validador.validate(new PerfilController.CambioDeNombre("x".repeat(161))))
                .extracting(v -> v.getMessage()).containsExactly("El nombre no puede pasar de 160 caracteres");
    }

    @Test
    void un_token_valido_de_una_cuenta_que_ya_no_existe_es_una_sesion_muerta_401() {
        Principal fantasma = () -> UUID.randomUUID().toString();
        when(users.findById(any(UUID.class))).thenAnswer(inv ->
                inv.getArgument(0).equals(ana.getId()) ? Optional.of(ana) : Optional.empty());

        assertThatThrownBy(() -> perfil.yo(fantasma))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // -------------------------------------------------------- registro / login

    @Test
    void el_registro_guarda_el_email_en_minusculas_y_rechaza_el_repetido_sin_importar_mayusculas() {
        when(users.existsByEmailIgnoreCase(anyString())).thenAnswer(inv ->
                ana.getEmail().equalsIgnoreCase(inv.getArgument(0)));

        var repetido = auth.registrar(new AuthController.RegistroRequest("ANA@uagrm.edu.bo", "contrasena-larga", "Otra Ana"));
        assertThat(repetido.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var nuevo = auth.registrar(new AuthController.RegistroRequest("  Beto@UAGRM.edu.bo ", "contrasena-larga", " Beto "));
        assertThat(nuevo.getStatusCode()).isEqualTo(HttpStatus.OK);
        var guardado = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        org.mockito.Mockito.verify(users).save(guardado.capture());
        assertThat(guardado.getValue().getEmail()).isEqualTo("beto@uagrm.edu.bo");
        assertThat(guardado.getValue().getFullName()).isEqualTo("Beto");
    }

    @Test
    void el_login_no_distingue_mayusculas_en_el_email() {
        var r = auth.iniciarSesion(new AuthController.LoginRequest("  ANA@Uagrm.edu.bo ", "contrasena-vieja"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void el_registro_valida_con_mensajes_en_espanol() {
        var mensajes = validador.validate(new AuthController.RegistroRequest("no-es-un-email", "corta", ""));

        assertThat(mensajes).extracting(v -> v.getMessage()).contains(
                "El email no es válido",
                "La contraseña debe tener al menos 8 caracteres",
                "El nombre es obligatorio");
    }

    private ResponseEntity<?> iniciarSesion(String contrasena) {
        return auth.iniciarSesion(new AuthController.LoginRequest("ana@uagrm.edu.bo", contrasena));
    }
}
