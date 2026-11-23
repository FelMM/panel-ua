# UA PAB — app Android

App del **Panel de Control – Unidad de Análisis** (Programa Académico de Bachillerato, U. de Chile).

- **Accesos:** el creador entra con la **contraseña del panel** y ve todos los módulos. Cada otra persona entra con su **código de acceso personal** y solo ve los módulos que el creador le asignó (lista en `modulos.json` → `accesos`, guardada como huella: los códigos y correos no se publican). Para que el módulo abra, además debe estar compartido con esa persona en claude.ai.
- Pide la **misma contraseña** que el panel web (la app guarda solo su huella SHA-256, no la contraseña). Vuelve a pedirla tras 10 minutos en segundo plano o con «Bloquear».
- Inicio con las tres secciones y sus 7 módulos: PAB, UChile, SAT (próximamente), EMS, Abandono, Carga Académica e Informes de Notas, más un acceso al panel web completo.
- Cada módulo se abre **dentro de la app**, en su propio navegador (no necesita Chrome). La primera vez pide iniciar sesión en Claude con correo y código por email (el ingreso con Google no funciona dentro de apps); la sesión queda guardada. Las descargas (PDF, Excel) quedan en Descargas/Panel UA.
- Ícono: el búho del asistente virtual.
- Android 7.0 o superior (usa la librería androidx.webkit).

`vista_previa.png` muestra cómo se ven las dos pantallas.

## Obtener el APK

### Opción A — Android Studio (en un computador)
1. Instalar Android Studio (gratis): https://developer.android.com/studio
2. *File → Open* y elegir la carpeta `PanelUA`. Esperar a que termine «Gradle sync» (descarga lo necesario la primera vez).
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
4. El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.

### Opción B — GitHub, sin instalar nada
1. Crear un repositorio privado en GitHub y subir el contenido de esta carpeta.
2. La acción «Compilar APK» (`.github/workflows/apk.yml`) corre sola; al terminar (unos 5 min), en *Actions → la ejecución → Artifacts* se descarga `PanelUA-apk` con `app-release.apk`.

### Instalar en el teléfono
Copiar el `.apk` al teléfono y abrirlo. Android pedirá permitir «instalar apps desconocidas» para el administrador de archivos o el navegador; aceptar solo para esa instalación.

## Cambios frecuentes
- **Activar o desactivar un módulo en la app:** editar `modulos.json` en este repositorio (true/false, mismos nombres que los interruptores del panel web: pab, uch, sat, enc1, enc2, enc3, notas). La app lo lee al abrirse; no hace falta reinstalarla. Un módulo desactivado se ve como «No disponible»; uno sin enlace, como «Próximamente».
- **Módulos o enlaces:** arreglo `MODS` al inicio de `MainActivity.java` (sección, rótulo, título, descripción, sigla, enlace; `null` = «Próximamente»).
- **Contraseña:** si cambia la del panel web, reemplazar `HASH` por el SHA-256 de la nueva (en una terminal: `printf '%s' 'nueva-clave' | sha256sum`).
- Al publicar una versión nueva, subir `versionCode` en `app/build.gradle`.

Nota: los accesos desde la app van directo a cada módulo, por lo que no quedan en el registro de «Accesos e ingresos» del panel web (ese registro cuenta los ingresos hechos en el panel).
