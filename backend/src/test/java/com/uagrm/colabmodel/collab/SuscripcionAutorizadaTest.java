package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.JwtService;
import com.uagrm.colabmodel.project.ProyectosEnMemoria;
import com.uagrm.colabmodel.project.Project;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * El hueco que cerraba el ADR-026: `StompAuthChannelInterceptor` solo
 * autenticaba el CONNECT, asi que cualquier usuario con un token valido podia
 * suscribirse a `/topic/diagram/{id}/...` de un proyecto AJENO y leer en vivo
 * lo que otros editaban.
 *
 * Se prueba el interceptor con frames STOMP armados a mano: es exactamente lo
 * que Spring le entrega antes de que el mensaje llegue al broker.
 */
class SuscripcionAutorizadaTest {

    private static final String SECRETO = "una-clave-de-prueba-de-al-menos-32-bytes-para-hs256";

    private ProyectosEnMemoria datos;
    private WsSessionRegistry sesiones;
    private JwtService jwt;
    private StompAuthChannelInterceptor interceptor;
    private final MessageChannel canal = mock(MessageChannel.class);

    private AppUser ana;      // propietaria
    private AppUser beto;     // EDITOR
    private AppUser carla;    // VIEWER
    private AppUser extrano;  // no es miembro
    private UUID diagramaId;

    @BeforeEach
    void prepararUnProyectoAjenoAlExtrano() {
        datos = new ProyectosEnMemoria();
        sesiones = new WsSessionRegistry();
        jwt = new JwtService(SECRETO, 60);
        interceptor = new StompAuthChannelInterceptor(jwt, sesiones, datos.acceso);

        ana = datos.usuario("Ana");
        Project p = datos.proyecto(ana, "Biblioteca");
        diagramaId = datos.diagramas.values().iterator().next().getId();
        beto = datos.miembro(p, "Beto", Rol.EDITOR);
        carla = datos.miembro(p, "Carla", Rol.VIEWER);
        extrano = datos.usuario("Extrano");
    }

    // ------------------------------------------------------------- SUBSCRIBE

    @Test
    void un_no_miembro_no_se_suscribe_a_ningun_topico_de_un_diagrama_ajeno() {
        for (String tema : new String[]{"ops", "presence", "locks"}) {
            Message<?> pedido = suscripcion(extrano, "s-x", "sub-" + tema, "/topic/diagram/" + diagramaId + "/" + tema);

            assertThatThrownBy(() -> interceptor.preSend(pedido, canal))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("No sos miembro");
        }
        // Y no quedo registrado nada: no es una suscripcion a medias.
        assertThat(sesiones.suscripcionesQue(s -> true)).isEmpty();
    }

    @Test
    void los_tres_roles_si_se_suscriben() {
        for (AppUser miembro : new AppUser[]{ana, beto, carla}) {
            Message<?> pedido = suscripcion(miembro, "s-" + miembro.getFullName(), "sub-0",
                    "/topic/diagram/" + diagramaId + "/ops");

            assertThat(interceptor.preSend(pedido, canal)).isSameAs(pedido);
            assertThat(sesiones.estaSuscrita("s-" + miembro.getFullName(),
                    "/topic/diagram/" + diagramaId + "/ops")).isTrue();
        }
    }

    @Test
    void un_diagrama_que_no_existe_se_niega_igual_que_uno_ajeno() {
        Message<?> pedido = suscripcion(ana, "s-ana", "sub-0", "/topic/diagram/" + UUID.randomUUID() + "/ops");

        assertThatThrownBy(() -> interceptor.preSend(pedido, canal)).isInstanceOf(MessagingException.class);
    }

    @Test
    void los_destinos_que_el_servidor_no_publica_se_niegan() {
        for (String destino : new String[]{
                "/topic/otra-cosa",
                "/topic/diagram/" + diagramaId,                               // sin el tema
                "/topic/diagram/" + diagramaId + "/ops/extra",
                "/topic/diagram/no-es-un-uuid/ops",
                "/topic/diagram/------------------------------------/ops",    // 36 caracteres, pero no un UUID
                "/queue/errors-usersesion-de-otro",                            // la cola privada de OTRA sesion
                "/topic/**"}) {
            Message<?> pedido = suscripcion(ana, "s-ana", "sub-0", destino);

            assertThatThrownBy(() -> interceptor.preSend(pedido, canal))
                    .as("SUBSCRIBE a %s", destino)
                    .isInstanceOf(MessagingException.class);
        }
    }

    @Test
    void la_cola_privada_de_cada_usuario_esta_permitida() {
        // /user/queue/... lo resuelve Spring por sesion: cada quien recibe solo lo suyo.
        for (String cola : new String[]{"/user/queue/errors", "/user/queue/membresia"}) {
            Message<?> pedido = suscripcion(extrano, "s-x", "sub-" + cola, cola);

            assertThat(interceptor.preSend(pedido, canal)).isSameAs(pedido);
        }
    }

    @Test
    void al_desuscribirse_deja_de_constar() {
        String destino = "/topic/diagram/" + diagramaId + "/ops";
        interceptor.preSend(suscripcion(ana, "s-ana", "sub-0", destino), canal);

        interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, ana, "s-ana", "sub-0", null), canal);

        assertThat(sesiones.estaSuscrita("s-ana", destino)).isFalse();
    }

    // ------------------------------------------------------------------ SEND

    @Test
    void un_viewer_y_un_no_miembro_no_toman_locks_un_editor_si() {
        String destino = "/app/diagram/" + diagramaId + "/lock";

        assertThat(interceptor.preSend(envio(carla, "s-carla", destino), canal)).isNull();     // VIEWER
        assertThat(interceptor.preSend(envio(extrano, "s-x", destino), canal)).isNull();       // ajeno
        assertThat(interceptor.preSend(envio(beto, "s-beto", destino), canal)).isNotNull();    // EDITOR
        assertThat(interceptor.preSend(envio(ana, "s-ana", destino), canal)).isNotNull();      // OWNER
    }

    @Test
    void soltar_un_lock_es_cosa_de_miembros() {
        String destino = "/app/diagram/" + diagramaId + "/unlock";

        assertThat(interceptor.preSend(envio(extrano, "s-x", destino), canal)).isNull();
        assertThat(interceptor.preSend(envio(carla, "s-carla", destino), canal)).isNotNull();
    }

    @Test
    void la_presencia_solo_la_envia_quien_esta_suscrito_a_ella() {
        String base = "/topic/diagram/" + diagramaId;
        String envio = "/app/diagram/" + diagramaId + "/presence";
        interceptor.preSend(suscripcion(carla, "s-carla", "sub-0", base + "/presence"), canal);

        assertThat(interceptor.preSend(envio(carla, "s-carla", envio), canal)).isNotNull();   // VIEWER suscrito
        assertThat(interceptor.preSend(envio(extrano, "s-x", envio), canal)).isNull();        // nunca pudo suscribirse
        // Sacar la suscripcion --lo que pasa cuando lo quitan del proyecto-- apaga el permiso.
        sesiones.desuscribir("s-carla", "sub-0");
        assertThat(interceptor.preSend(envio(carla, "s-carla", envio), canal)).isNull();
    }

    @Test
    void los_comandos_pasan_siempre_el_bus_los_rechaza_con_su_propio_aviso_privado() {
        // Un VIEWER que manda un comando recibe FORBIDDEN por su cola de errores (EC-11):
        // si el interceptor lo descartara en silencio, no se enteraria de nada.
        String destino = "/app/diagram/" + diagramaId + "/op";

        assertThat(interceptor.preSend(envio(carla, "s-carla", destino), canal)).isNotNull();
        assertThat(interceptor.preSend(envio(extrano, "s-x", destino), canal)).isNotNull();
    }

    @Test
    void un_envio_a_otro_lado_no_es_cosa_de_este_control() {
        Message<?> mensaje = envio(extrano, "s-x", "/app/otra-cosa");

        assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
    }

    // --------------------------------------------------------------- CONNECT

    @Test
    void el_connect_sigue_exigiendo_un_token_valido() {
        StompHeaderAccessor sinToken = StompHeaderAccessor.create(StompCommand.CONNECT);
        sinToken.setSessionId("s-1");
        assertThatThrownBy(() -> interceptor.preSend(build(sinToken), canal))
                .isInstanceOf(MessagingException.class).hasMessageContaining("Authorization");

        StompHeaderAccessor tokenMalo = StompHeaderAccessor.create(StompCommand.CONNECT);
        tokenMalo.setSessionId("s-1");
        tokenMalo.setNativeHeader("Authorization", "Bearer esto-no-es-un-jwt");
        assertThatThrownBy(() -> interceptor.preSend(build(tokenMalo), canal))
                .isInstanceOf(MessagingException.class).hasMessageContaining("Token invalido");

        StompHeaderAccessor bueno = StompHeaderAccessor.create(StompCommand.CONNECT);
        bueno.setSessionId("s-2");
        bueno.setNativeHeader("Authorization", "Bearer " + jwt.emitir(ana));
        interceptor.preSend(build(bueno), canal);
        assertThat(bueno.getUser().getName()).isEqualTo(ana.getId().toString());
    }

    // ----------------------------------------------------------------- apoyo

    private Message<?> suscripcion(AppUser quien, String sesion, String subscriptionId, String destino) {
        return frame(StompCommand.SUBSCRIBE, quien, sesion, subscriptionId, destino);
    }

    private Message<?> envio(AppUser quien, String sesion, String destino) {
        return frame(StompCommand.SEND, quien, sesion, null, destino);
    }

    private Message<?> frame(StompCommand comando, AppUser quien, String sesion, String subscriptionId, String destino) {
        StompHeaderAccessor h = StompHeaderAccessor.create(comando);
        h.setSessionId(sesion);
        if (subscriptionId != null) h.setSubscriptionId(subscriptionId);
        if (destino != null) h.setDestination(destino);
        Principal principal = () -> quien.getId().toString();
        h.setUser(principal);
        return build(h);
    }

    private static Message<?> build(StompHeaderAccessor h) {
        h.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }
}
