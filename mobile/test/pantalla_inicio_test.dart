import 'package:colabmodel_app/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('la pantalla inicial ofrece Asistente y Ver diagrama en vivo', (tester) async {
    await tester.pumpWidget(const ColabModelApp());
    expect(find.text('Asistente'), findsOneWidget);
    expect(find.text('Ver diagrama en vivo'), findsOneWidget);
  });

  testWidgets('Asistente pide la direccion del backend', (tester) async {
    await tester.pumpWidget(const ColabModelApp());
    await tester.tap(find.text('Asistente'));
    await tester.pumpAndSettle();
    expect(find.text('Direccion del backend'), findsOneWidget);
    expect(find.text('Conectar'), findsOneWidget);
  });

  testWidgets('Ver diagrama en vivo abre el login de la herramienta', (tester) async {
    await tester.pumpWidget(const ColabModelApp());
    await tester.tap(find.text('Ver diagrama en vivo'));
    await tester.pumpAndSettle();
    expect(find.text('Direccion de la herramienta'), findsOneWidget);
    expect(find.text('Contrasena'), findsOneWidget);
    expect(find.text('Entrar'), findsOneWidget);
  });
}
