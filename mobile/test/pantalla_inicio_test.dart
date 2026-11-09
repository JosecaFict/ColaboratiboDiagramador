import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:colabmodel_app/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({});
  });

  testWidgets('la pantalla inicial ofrece Asistente y Diagramas en vivo',
      (tester) async {
    await tester.pumpWidget(const ColabModelApp());
    expect(find.text('Asistente'), findsOneWidget);
    expect(find.text('Diagramas en vivo'), findsOneWidget);
  });

  testWidgets('Asistente pide la direccion del backend', (tester) async {
    await tester.pumpWidget(const ColabModelApp());
    await tester.tap(find.text('Asistente'));
    await tester.pumpAndSettle();
    expect(find.text('Direccion del backend'), findsOneWidget);
    expect(find.text('Conectar'), findsOneWidget);
  });

  testWidgets('Diagramas en vivo abre el login de la herramienta',
      (tester) async {
    await tester.pumpWidget(const ColabModelApp());
    await tester.tap(find.text('Diagramas en vivo'));
    await tester.pumpAndSettle();
    expect(find.text('Dirección de la herramienta'), findsOneWidget);
    expect(find.text('Contraseña'), findsOneWidget);
    expect(find.text('Entrar'), findsOneWidget);
  });
}
