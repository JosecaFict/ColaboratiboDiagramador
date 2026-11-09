import 'dart:async';
import 'package:colabmodel_app/editor/controlador_editor.dart';
import 'package:colabmodel_app/editor/pantallas/entrada.dart';
import 'package:colabmodel_app/sesion/entrada_sesion.dart';
import 'package:colabmodel_app/sesion/servicio_sesion.dart';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import '../editor/pantallas/editor_falso.dart';
import 'servicio_sesion_test.dart'
    show MemoriaSesion, servicio, respuesta, datos, token;

class BancoPantalla {
  final ServicioSesion s;
  bool cerrado = false;
  BancoPantalla(this.s);
  Future<void> cerrar(WidgetTester t) async {
    await t.pumpWidget(const SizedBox.shrink());
    if (!cerrado) {
      s.dispose();
      cerrado = true;
    }
    await t.pump();
  }
}

Future<BancoPantalla> abrir(WidgetTester t, MemoriaSesion m,
    Future<http.Response> Function(http.Request) responder) async {
  t.view.physicalSize = const Size(412, 915);
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.reset);
  final b = BancoPantalla(servicio(m, responder));
  addTearDown(() {
    if (!b.cerrado) b.s.dispose();
  });
  await t.pumpWidget(MaterialApp(home: EntradaSesion(servicio: b.s)));
  await t.pumpAndSettle();
  return b;
}

Future<void> campo(WidgetTester t, int i, String valor) async {
  final f = find.byType(TextFormField).at(i);
  await t.ensureVisible(f);
  await t.enterText(f, valor);
}

Future<void> credenciales(WidgetTester t, {bool registro = false}) async {
  await campo(t, 0, 'h:8080');
  if (registro) {
    await campo(t, 1, 'Ana');
    await campo(t, 2, 'ana@ejemplo.com');
    await campo(t, 3, 'secreto123');
    await campo(t, 4, 'secreto123');
  } else {
    await campo(t, 1, 'ana@ejemplo.com');
    await campo(t, 2, 'secreto123');
  }
}

Future<void> boton(WidgetTester t, String texto) async {
  final f = find.widgetWithText(FilledButton, texto);
  await t.ensureVisible(f);
  await t.tap(f);
  await t.pumpAndSettle();
}

http.Response proyectos() => respuesta([
      {'id': 'p', 'name': 'Biblioteca', 'myRole': 'EDITOR', 'diagramCount': 1}
    ]);
http.Response diagramas() => respuesta({
      'diagrams': [
        {'id': 'd', 'name': 'Principal', 'currentSeq': 0}
      ]
    });

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    fabricaDeEditor = null;
  });
  tearDown(() => fabricaDeEditor = null);
  testWidgets('campos vacíos y registro inválido no envían datos', (t) async {
    var llamadas = 0;
    final b = await abrir(t, MemoriaSesion(), (_) async {
      llamadas++;
      return respuesta(token());
    });
    await boton(t, 'Entrar');
    expect(find.textContaining('Ingresá la dirección'), findsOneWidget);
    expect(llamadas, 0);
    await t.tap(find.text('Creá tu cuenta'));
    await t.pumpAndSettle();
    await credenciales(t, registro: true);
    await campo(t, 3, 'corta');
    await campo(t, 4, 'distinta');
    await boton(t, 'Crear cuenta');
    expect(find.textContaining('al menos 8'), findsOneWidget);
    expect(find.text('Las contraseñas no coinciden.'), findsOneWidget);
    expect(llamadas, 0);
    await b.cerrar(t);
  });
  testWidgets('ver contraseña y entrar llega a Mis proyectos', (t) async {
    final m = MemoriaSesion();
    final b = await abrir(
        t,
        m,
        (r) async =>
            r.url.path.endsWith('/login') ? respuesta(token()) : proyectos());
    await credenciales(t);
    await t.tap(find.byTooltip('Mostrar contraseña'));
    await t.pump();
    expect(
        t
            .widget<TextField>(find.descendant(
                of: find.byType(TextFormField).last,
                matching: find.byType(TextField)))
            .obscureText,
        isFalse);
    await boton(t, 'Entrar');
    expect(find.text('Mis proyectos'), findsOneWidget);
    expect(m.contenido, isNotNull);
    await b.cerrar(t);
  });
  testWidgets(
      'Enter envía una sola vez y el botón queda bloqueado durante la espera',
      (t) async {
    final respuestaLogin = Completer<http.Response>();
    var envios = 0;
    final b = await abrir(t, MemoriaSesion(), (r) async {
      if (r.url.path.endsWith('/login')) {
        envios++;
        return respuestaLogin.future;
      }
      return proyectos();
    });
    await credenciales(t);
    await t.testTextInput.receiveAction(TextInputAction.done);
    await t.pump();
    expect(t.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNull);
    respuestaLogin.complete(respuesta(token()));
    await t.pumpAndSettle();
    expect(envios, 1);
    expect(find.text('Mis proyectos'), findsOneWidget);
    await b.cerrar(t);
  });
  for (final codigo in [400, 401, 409]) {
    testWidgets(
        'error $codigo en acceso queda visible sin navegar ni guardar sesión',
        (t) async {
      final m = MemoriaSesion();
      final b = await abrir(t, m,
          (_) async => respuesta({'mensaje': 'Revisá los datos'}, codigo));
      if (codigo != 401) {
        await t.tap(find.text('Creá tu cuenta'));
        await t.pumpAndSettle();
      }
      await credenciales(t, registro: codigo != 401);
      await boton(t, codigo == 401 ? 'Entrar' : 'Crear cuenta');
      expect(
          find.textContaining(codigo == 409
              ? 'Ese email ya está registrado'
              : codigo == 401
                  ? 'no son correctos'
                  : 'Revisá los datos'),
          findsOneWidget);
      expect(find.text('Mis proyectos'), findsNothing);
      expect(m.contenido, isNull);
      await b.cerrar(t);
    });
  }
  testWidgets('crear cuenta entra directo y guarda fullName del servidor',
      (t) async {
    final m = MemoriaSesion();
    final b = await abrir(
        t,
        m,
        (r) async => r.url.path.endsWith('/register')
            ? respuesta(token())
            : proyectos());
    await t.tap(find.text('Creá tu cuenta'));
    await t.pumpAndSettle();
    await credenciales(t, registro: true);
    await boton(t, 'Crear cuenta');
    expect(find.text('Mis proyectos'), findsOneWidget);
    expect(b.s.datos!.fullName, 'Ana Pérez');
    await b.cerrar(t);
  });
  testWidgets('Atrás desde registro vuelve al login y conserva la dirección',
      (t) async {
    final b = await abrir(t, MemoriaSesion(), (_) async => respuesta([]));
    await campo(t, 0, '192.168.0.7:8080');
    await t.tap(find.text('Creá tu cuenta'));
    await t.pumpAndSettle();
    await t.binding.handlePopRoute();
    await t.pumpAndSettle();
    expect(find.text('Iniciar sesión'), findsOneWidget);
    expect(
        t
            .widget<TextFormField>(find.byType(TextFormField).first)
            .controller!
            .text,
        '192.168.0.7:8080');
    await b.cerrar(t);
  });
  testWidgets(
      'sesión restaurada evita pedir contraseña; un 401 muestra login y aviso',
      (t) async {
    final m = MemoriaSesion()..contenido = datos.serializar();
    var vencida = false;
    final b = await abrir(
        t, m, (_) async => vencida ? respuesta({}, 401) : proyectos());
    expect(find.text('Mis proyectos'), findsOneWidget);
    expect(find.byType(TextFormField), findsNothing);
    vencida = true;
    await t.tap(find.byTooltip('Actualizar proyectos'));
    await t.pumpAndSettle();
    expect(find.text('Iniciar sesión'), findsOneWidget);
    expect(find.text(ServicioSesion.avisoVencida), findsOneWidget);
    expect(m.contenido, isNull);
    await b.cerrar(t);
  });
  testWidgets('restaurar sin red conserva credenciales y permite reintentar',
      (t) async {
    var sinRed = true;
    final m = MemoriaSesion()..contenido = datos.serializar();
    final b = await abrir(t, m, (_) async {
      if (sinRed) throw http.ClientException('sin ruta');
      return proyectos();
    });
    expect(find.text('Reintentar'), findsOneWidget);
    expect(m.contenido, isNotNull);
    sinRed = false;
    await boton(t, 'Reintentar');
    expect(find.text('Mis proyectos'), findsOneWidget);
    await b.cerrar(t);
  });
  testWidgets(
      'cerrar sesión confirma, admite cancelar y no permite volver con Atrás',
      (t) async {
    final m = MemoriaSesion()..contenido = datos.serializar();
    var salidas = 0;
    final b = await abrir(t, m, (r) async {
      if (r.url.path.endsWith('/logout')) {
        salidas++;
        return http.Response('', 204);
      }
      return proyectos();
    });
    await t.tap(find.byTooltip('Cerrar sesión'));
    await t.pumpAndSettle();
    await t.tap(find.text('Cancelar'));
    await t.pumpAndSettle();
    expect(salidas, 0);
    await t.tap(find.byTooltip('Cerrar sesión'));
    await t.pumpAndSettle();
    await boton(t, 'Cerrar sesión');
    expect(find.text('Iniciar sesión'), findsOneWidget);
    expect(salidas, 1);
    expect(m.contenido, isNull);
    await t.binding.handlePopRoute();
    await t.pumpAndSettle();
    expect(find.text('Mis proyectos'), findsNothing);
    await b.cerrar(t);
  });
  for (final editor in [false, true]) {
    testWidgets(
        '401 en ${editor ? 'editor' : 'visor'} retira todas las rutas y avisa sesión vencida',
        (t) async {
      fabricaDeEditor = crearEditor;
      final m = MemoriaSesion()..contenido = datos.serializar();
      final b = await abrir(t, m, (r) async {
        if (r.url.path.endsWith('/projects')) return proyectos();
        if (r.url.path.endsWith('/projects/p')) return diagramas();
        return respuesta({}, 401);
      });
      await t.tap(find.text('Biblioteca'));
      await t.pumpAndSettle();
      await t.tap(
          editor ? find.text('Principal') : find.byTooltip('Ver sin editar'));
      await t.pumpAndSettle();
      expect(find.text('Iniciar sesión'), findsOneWidget);
      expect(find.text(ServicioSesion.avisoVencida), findsOneWidget);
      expect(find.text('Principal'), findsNothing);
      expect(m.contenido, isNull);
      await b.cerrar(t);
    });
  }
  testWidgets(
      'el editor recibe fullName para su lock y el cajón permite cerrar sesión',
      (t) async {
    String? nombre;
    late EditorFalso e;
    fabricaDeEditor = (
        {required cliente,
        required diagramId,
        required rol,
        required nombreUsuario}) {
      nombre = nombreUsuario;
      return e = EditorFalso(modelo: modeloDePrueba());
    };
    final m = MemoriaSesion()..contenido = datos.serializar();
    final b = await abrir(t, m, (r) async {
      if (r.url.path.endsWith('/projects')) return proyectos();
      if (r.url.path.endsWith('/logout')) return http.Response('', 204);
      return diagramas();
    });
    await t.tap(find.text('Biblioteca'));
    await t.pumpAndSettle();
    await t.tap(find.byTooltip('Editar diagrama'));
    await t.pumpAndSettle();
    expect(nombre, 'Ana Pérez');
    t.state<ScaffoldState>(find.byType(Scaffold).last).openDrawer();
    await t.pumpAndSettle();
    await t.tap(find.text('Cerrar sesión'));
    await t.pumpAndSettle();
    await boton(t, 'Cerrar sesión');
    expect(find.text('Iniciar sesión'), findsOneWidget);
    expect(e.cerrado, isTrue);
    expect(m.contenido, isNull);
    await b.cerrar(t);
  });
  testWidgets('quien puede editar entra al editor con el toque normal',
      (t) async {
    var abiertos = 0;
    fabricaDeEditor = (
        {required cliente,
        required diagramId,
        required rol,
        required nombreUsuario}) {
      abiertos++;
      return EditorFalso(modelo: modeloDePrueba());
    };
    final m = MemoriaSesion()..contenido = datos.serializar();
    final b = await abrir(t, m, (r) async {
      if (r.url.path.endsWith('/projects')) return proyectos();
      return diagramas();
    });
    await t.tap(find.text('Biblioteca'));
    await t.pumpAndSettle();
    expect(find.byTooltip('Ver sin editar'), findsOneWidget);
    await t.tap(find.text('Principal'));
    await t.pumpAndSettle();
    expect(abiertos, 1);
    expect(find.text('Paleta'), findsOneWidget);
    await b.cerrar(t);
  });
  testWidgets('el Observador no ve lapiz ni ojo y el toque no abre el editor',
      (t) async {
    var abiertos = 0;
    fabricaDeEditor = (
        {required cliente,
        required diagramId,
        required rol,
        required nombreUsuario}) {
      abiertos++;
      return EditorFalso(modelo: modeloDePrueba());
    };
    final m = MemoriaSesion()..contenido = datos.serializar();
    final b = await abrir(t, m, (r) async {
      if (r.url.path.endsWith('/projects')) {
        return respuesta([
          {'id': 'p', 'name': 'Biblioteca', 'myRole': 'VIEWER', 'diagramCount': 1}
        ]);
      }
      if (r.url.path.endsWith('/projects/p')) return diagramas();
      return respuesta({}, 401);
    });
    await t.tap(find.text('Biblioteca'));
    await t.pumpAndSettle();
    expect(find.byTooltip('Editar diagrama'), findsNothing);
    expect(find.byTooltip('Ver sin editar'), findsNothing);
    expect(find.textContaining('solo lectura'), findsOneWidget);
    await t.tap(find.text('Principal'));
    await t.pumpAndSettle();
    expect(abiertos, 0);
    await b.cerrar(t);
  });
}
