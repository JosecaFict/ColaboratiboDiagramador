import 'package:colabmodel_app/main.dart';
import 'package:colabmodel_app/visor/almacen_local.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<void> abrirApp(WidgetTester tester) async {
  tester.view.physicalSize = const Size(412, 915);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(const ColabModelApp());
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({});
  });

  testWidgets('sin sesion guardada la app abre en el login', (tester) async {
    await abrirApp(tester);
    expect(find.text('Iniciar sesión'), findsOneWidget);
    expect(find.text('Dirección de la herramienta'), findsOneWidget);
    expect(find.text('Contraseña'), findsOneWidget);
    expect(find.text('Entrar'), findsOneWidget);
    expect(find.text('Usar el Asistente sin cuenta'), findsOneWidget);
  });

  testWidgets('el Asistente se abre desde el login, sin cuenta', (tester) async {
    await abrirApp(tester);
    final boton = find.text('Usar el Asistente sin cuenta');
    await tester.ensureVisible(boton);
    await tester.pumpAndSettle();
    await tester.tap(boton);
    await tester.pumpAndSettle();
    expect(find.text('Direccion del backend'), findsOneWidget);
    expect(find.text('Conectar'), findsOneWidget);
  });

  testWidgets('el atajo de USB completa la direccion', (tester) async {
    await abrirApp(tester);
    await tester.tap(find.text('Por USB: 127.0.0.1:8080'));
    await tester.pump();
    expect(find.widgetWithText(TextFormField, '127.0.0.1:8080'), findsOneWidget);
  });

  testWidgets('con un servidor recordado el campo va plegado y se puede cambiar',
      (tester) async {
    await AlmacenLocal().guardarConexion('10.0.0.5:8080', 'ana@ejemplo.com');
    await abrirApp(tester);
    expect(find.text('Dirección de la herramienta'), findsNothing);
    expect(find.text('10.0.0.5:8080'), findsOneWidget);
    expect(find.byType(TextFormField), findsNWidgets(2));
    await tester.tap(find.text('Cambiar'));
    await tester.pump();
    expect(find.text('Dirección de la herramienta'), findsOneWidget);
    expect(find.byType(TextFormField), findsNWidgets(3));
  });
}
