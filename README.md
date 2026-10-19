# Garmin HR Monitor (Android 16)

## Estado real de compatibilidad

Esta versión **sí implementa BLE Heart Rate Service (0x180D)** con reconexión, gráfico y exportación CSV.
**No implementa la recepción ANT+**: detecta algunos paquetes ANT+, pero eso no equivale a leer un sensor ANT+.
El Garmin Forerunner 235 puede transmitir frecuencia cardíaca mediante ANT+, pero puede no anunciar el servicio BLE Heart Rate. En tal caso la aplicación no recibirá BPM del reloj. El soporte ANT+ requiere integrar un SDK ANT+ compatible, sus licencias y verificar que el teléfono tenga hardware y servicios ANT+ funcionales. No se garantiza que un Galaxy A16 pueda recibir ANT+ solo por instalar una aplicación.

## Generar APK desde el teléfono sin instalar Android Studio

1. Crea un repositorio GitHub y sube **el contenido** de esta carpeta, incluida `.github/workflows/android.yml`.
2. Abre la pestaña **Actions** y selecciona **Compilar APK** > **Run workflow**.
3. Al finalizar, descarga el artefacto `GarminHRMonitor-APK` y extrae `app-debug.apk`.
4. Instala el APK en Android (habilita instalación de apps desde esa fuente si te lo solicita).
5. Da permisos Bluetooth, activa la emisión del sensor y abre la app.

El APK de debug está firmado automáticamente para pruebas, no para distribución en Play Store.

## Privacidad y límites

No hay servidor ni envío automático de pulsaciones. Las muestras solo permanecen en memoria durante la sesión y pueden compartirse manualmente como texto CSV. Los umbrales de color son ilustrativos, no zonas clínicas ni personalizadas. La pantalla se mantiene activa mientras la actividad está abierta. La reconexión funciona mientras la actividad permanece abierta; no hay servicio en segundo plano.
