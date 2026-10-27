# Control OT – Android (Kotlin) · Bluetooth RFCOMM cifrado

App única (una sola APK) que se instala en **dos teléfonos Android**:

| Rol | Pantalla | Función |
|---|---|---|
| **Nodo Gateway** (Teléfono 1) | `GatewayActivity` | Servidor Bluetooth RFCOMM junto a la máquina. Simula temperatura y distancia (en el proyecto final las entrega el Arduino). Aplica la lógica de seguridad. |
| **Operador HMI** (Teléfono 2) | `PanelControlActivity` | Cliente: monitorea, controla (enfriamiento, límite RPM, REDUCIR VELOCIDAD, PARADA DE EMERGENCIA) y recibe alertas. |

Flujo: `LoginActivity` → `RoleActivity` (elige rol + clave de enlace) → Panel o Gateway.

## Cómo probar
1. Abre la carpeta en Android Studio (Hedgehog o superior), espera el *Gradle Sync* y ejecuta en 2 teléfonos (Android 8.0+, Bluetooth).
2. **Empareja** los teléfonos desde *Ajustes > Bluetooth* (o usa el botón *Hacer visible* del Gateway).
3. Login de prueba: `OP-001` / `1234` (o `ADMIN` / `admin123`).
4. Teléfono 1: rol **Nodo Gateway** → *Iniciar servidor*.
5. Teléfono 2: rol **Operador HMI** → *Conectar* → elige el Gateway.
6. Mueve el slider de distancia del Gateway: >2 m (segura), 1–2 m (alerta preventiva + sonido + vibración), <1 m (alarma continua + parada automática).
7. La **clave de enlace** (pantalla de rol) debe ser igual en ambos teléfonos (por defecto `OTControl-2026`).

## Seguridad (ISO/IEC 27001 / 27400) y lineamientos OT
- **Control de acceso**: login obligatorio; contraseñas solo como hash PBKDF2-HMAC-SHA256 con sal; bloqueo 30 s tras 3 intentos fallidos; actividades protegidas sin sesión.
- **Cifrado en tránsito**: cada trama (telemetría y comandos) va con **AES-256-GCM**, clave derivada con PBKDF2. Tramas alteradas o repetidas (número de secuencia) se descartan.
- **Air-gap**: la app no declara permiso `INTERNET`; comunicación solo por Bluetooth de corto alcance (línea de vista).
- **Disponibilidad / integridad**: *heartbeat* cada 2 s; enlace caído a los 6 s; cierre limpio de sockets; E/S en corrutinas (UI nunca bloqueada).
- **Seguridad funcional**: la parada de emergencia tiene prioridad absoluta; corte automático a < 1 m; **estado seguro** (motor detenido) si se pierde el enlace; el rearme solo se hace de forma local en el Gateway y únicamente si no hay objeto a < 1 m; acceso exclusivo de un operador.
- Permisos Bluetooth solicitados en tiempo de ejecución (Android 12+).

## Escenarios de prueba (rúbrica 2.1.4.8)
Cambios bruscos de distancia (<1 m / 1–2 m / >2 m), envío simultáneo de comandos, desconexión y reconexión del operador, clave de enlace distinta (no se acepta ninguna trama), Bluetooth apagado, permisos denegados.

## Estructura
```
app/src/main/java/com/example/otcontrol/
  LoginActivity · RoleActivity · PanelControlActivity · GatewayActivity
  SecureLink (RFCOMM + heartbeat) · FrameCipher (AES-GCM) · AuthManager
  Protocol (tramas, zonas) · AlertNotifier · ProximityBarView · BtPermissions
```

---

## Versión sumativa (Unidad 2): almacenamiento con SQLite
Se agregó una base de datos local **SQLite** (`Db.kt`), sin internet:
- **users**: el login se valida contra esta tabla (contraseña solo como hash PBKDF2 con sal).
- **readings**: temperatura, RPM, distancia, zona y estado del motor (una lectura cada 2 s).
- **events**: alertas, comandos, paradas y cambios de enlace.
- Pantalla **Historial** (`HistoryActivity`): estadísticas, lista de lecturas y de eventos, y borrado del historial. Se abre desde el Gateway y desde el Operador.
- Consultas con parámetros (`?`) para evitar inyección SQL.
- El `applicationId` es `com.example.otcontrol.sumativa`, así se puede instalar junto a la versión formativa.
