# Modelos canonicos de regresion

Estos 7 modelos se generan y **compilan en CI en cada push**. Si uno rompe,
se sabe ese dia y no el 22 a medianoche.

| # | Archivo | Que ejercita | Reglas |
|---|---|---|---|
| 1 | 01-uno-a-uno.json        | `@OneToOne` en el lado propietario | 1, 3 |
| 2 | 02-uno-a-muchos.json     | FK en el lado "muchos", `mappedBy` | 3, 7 |
| 3 | 03-muchos-a-muchos.json  | Tabla puente | 3 |
| 4 | 04-herencia.json         | Estrategia JOINED, clase abstracta | 4 |
| 5 | 05-clase-asociativa.json | N:M con atributos -> entidad intermedia | 3, ambiguedad A-4 |
| 6 | 06-nombres-acentos.json  | **Ñ, tildes, palabras reservadas, nombres largos** | 8 |
| 7 | 07-reflexiva-doble.json  | Auto-relacion + 2 relaciones entre las mismas clases | 3 |

Los dos ultimos son los que mas probablemente aporte el docente el dia de la
presentacion, y los que rompen a un generador ingenuo.
