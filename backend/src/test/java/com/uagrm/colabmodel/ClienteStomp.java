package com.uagrm.colabmodel;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Una pestana del navegador, para las pruebas: un cliente STOMP real contra el
 * servidor real, con su JWT en el CONNECT, igual que `wsClient.ts`.
 *
 * Cada suscripcion junta lo que llega en una cola, y `siguiente` espera un
 * mensaje o devuelve null. Ese null es la prueba de que algo NO llego.
 */
class ClienteStomp implements AutoCloseable {

    private final WebSocketStompClient cliente;
    private final StompSession sesion;
    private final Map<String, BlockingQueue<JsonNode>> colas = new ConcurrentHashMap<>();
    /** Los frames ERROR del servidor: lo que recibe un cliente al que le niegan una suscripcion. */
    final BlockingQueue<String> errores;

    private ClienteStomp(WebSocketStompClient cliente, StompSession sesion, BlockingQueue<String> errores) {
        this.cliente = cliente;
        this.sesion = sesion;
        this.errores = errores;
    }

    static ClienteStomp conectar(int puerto, String token) throws Exception {
        WebSocketStompClient cliente = new WebSocketStompClient(new StandardWebSocketClient());
        cliente.setMessageConverter(new MappingJackson2MessageConverter());

        BlockingQueue<String> errores = new LinkedBlockingQueue<>();
        StompHeaders conexion = new StompHeaders();
        conexion.add("Authorization", "Bearer " + token);

        // Un endpoint con SockJS tambien atiende el WebSocket "crudo" en /ws/websocket.
        StompSession sesion = cliente.connectAsync("ws://localhost:" + puerto + "/ws/websocket",
                new WebSocketHttpHeaders(), conexion, new StompSessionHandlerAdapter() {
                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        errores.add(String.valueOf(headers.getFirst("message")));
                    }
                }).get(10, TimeUnit.SECONDS);

        return new ClienteStomp(cliente, sesion, errores);
    }

    void suscribir(String destino) {
        BlockingQueue<JsonNode> cola = colas.computeIfAbsent(destino, d -> new LinkedBlockingQueue<>());
        sesion.subscribe(destino, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return JsonNode.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { cola.add((JsonNode) payload); }
        });
    }

    void enviar(String destino, Object cuerpo) {
        sesion.send(destino, cuerpo);
    }

    /** El proximo mensaje de esa suscripcion, o null si no llega en `milisegundos`. */
    JsonNode siguiente(String destino, long milisegundos) throws InterruptedException {
        return colas.get(destino).poll(milisegundos, TimeUnit.MILLISECONDS);
    }

    boolean conectado() {
        return sesion.isConnected();
    }

    @Override
    public void close() {
        if (sesion.isConnected()) sesion.disconnect();
        cliente.stop();
    }
}
