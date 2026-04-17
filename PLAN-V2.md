# Talk2Claw v2 — Plan

## Objetivo
Convertir Talk2Claw de una app simple push-to-talk con HTTP a un cliente de voz completo integrado con el gateway de OpenClaw como nodo oficial.

## Arquitectura actual (v1)
```
🎤 Mic → Android SpeechRecognizer (STT on-device, free)
    ↓
Text → HTTP POST /v1/chat/completions → respuesta completa
    ↓
Response text → Google Cloud TTS (Wavenet) → PCM → 🔊
```
- Push-to-talk (ya tiene continuous mode básico)
- Sin interrupción por voz
- Sin streaming (descarga audio completo, luego reproduce)
- Session aislada (`agent:main:talk2claw`)

## Arquitectura objetivo (v2)
```
🎤 Mic → Android SpeechRecognizer (STT on-device, free)
    ↓
Text → WebSocket Node (chat.send RPC) → sesión compartida
    ↓
Response text → Google Cloud TTS (Neural2/Studio) → streaming PCM → 🔊
    ↑
Interrupción por voz: mic activo durante playback → stop + nuevo turno
```

---

## Fases

### Fase 1 — Gateway Node Protocol (WebSocket)
Reemplazar HTTP REST por el protocolo de nodos oficial.

**Cambios:**
- [ ] Nuevo `GatewayNode.kt` — WebSocket client (OkHttp WebSocket)
  - Conexión a `ws://<host>:<port>` o `wss://` (Tailscale)
  - Device pairing flow (`devices.pair` → approve desde CLI)
  - Heartbeat/keepalive
  - RPC: `chat.send`, `chat.history`
- [ ] `ForegroundService` — mantener WebSocket vivo en background
  - Persistent notification "Talk2Claw conectado"
- [ ] Pantalla de pairing en Settings
  - Setup code (preferido) o manual (host/port)
  - Estado de conexión en tiempo real
- [ ] Eliminar `OpenClawBridge.kt` (HTTP)
- [ ] La session key ahora es la del nodo (sesión compartida con otros canales)

**Resultado:** La conversación por voz aparece en la misma sesión que Telegram/WhatsApp. `openclaw nodes status` muestra Talk2Claw.

### Fase 2 — Interrupción por voz
Permitir interrumpir a Claw mientras habla.

**Cambios:**
- [ ] Activar STT en paralelo durante TTS playback
  - Usar `AudioManager.MODE_IN_COMMUNICATION` para reducir echo
  - O: bajar volumen del speaker mientras se detecta voz
- [ ] En `MainViewModel`: si `onSpeechResult` llega durante `PipelineState.Speaking`:
  1. `tts.stop()` inmediatamente
  2. Procesar el nuevo texto del usuario
  3. Anotar en el mensaje a Claw que fue interrumpido (contexto)
- [ ] Gestión de echo: filtrar si el STT captura la propia voz de Claw
  - Heurística: ignorar resultados STT que coincidan parcialmente con el texto TTS en curso

**Resultado:** Conversación natural — puedes cortar a Claw a mitad de frase.

### Fase 3 — Streaming TTS (reducir latencia)
No esperar a tener todo el audio para empezar a reproducir.

**Cambios:**
- [ ] Chunking por frases: partir respuesta de Claw por `. ` `? ` `! ` `\n`
- [ ] Pipeline: sintetizar chunk 1 → reproducir chunk 1 + sintetizar chunk 2 → ...
- [ ] Cola de audio con `AudioTrack.MODE_STREAM` en vez de `MODE_STATIC`
- [ ] Alternativa: evaluar Google Cloud TTS Streaming API (`StreamingSynthesize` gRPC)
  - Requiere: `google-cloud-texttospeech` gRPC client
  - Pro: latencia mínima real
  - Con: más complejidad, dependencia gRPC

**Resultado:** Primera frase suena casi al instante (~300ms tras respuesta).

### Fase 4 — Voces mejoradas
**Cambios:**
- [ ] Probar voces (ranking calidad):
  1. `es-ES-Studio-B` (la más natural, $$)
  2. `es-ES-Neural2-B` (muy buena, $)
  3. `es-ES-Wavenet-B` (actual, decente, $)
- [ ] Selector de voz en Settings con botón "Preview"
- [ ] `speakingRate` ajustable (slider 0.8 – 1.3)
- [ ] Guardar preferencia de voz

### Fase 5 — Polish UX ✅
- [x] Animaciones de estado
- [x] Chat bubbles, dark/light theme, haptics
- [ ] Wake word "Oye Claw" (movido a Fase 7)
- [ ] Widget/tile quick-launch
- [ ] Historial de conversación persistente (Room DB)

### Fase 6 — Model & Thinking Selector (en progreso)
- [ ] Dropdown de modelo: Flash / Sonnet / Opus
- [ ] Toggle thinking on/off
- [ ] Se aplica vía /model y /reasoning como chat commands

### Fase 7 — Voice Match On-Device (pendiente)
- [ ] Speaker verification con modelo on-device (SpeechBrain/Resemblyzer)
- [ ] Enrollment de 3-5 frases desde Settings
- [ ] Cosine similarity threshold
- [ ] Combinar con wake word "Oye Claw"

---

## Prioridad
Fase 1 → 2 → 3 → 4 → 5

Fase 1 es la base (sin ella lo demás no tiene sentido).
Fase 2 es el mayor cambio en UX.
Fase 3 es el mayor cambio en latencia percibida.

## Stack
- Kotlin + Jetpack Compose
- OkHttp WebSocket
- Android SpeechRecognizer (STT, on-device, free)
- Google Cloud TTS API (REST, con API key existente)
- Foreground Service + Notification

## Notas
- Google Cloud TTS API key ya disponible en `env.vars`
- Gateway en `18789` accesible vía Tailscale
- STT en español (`es-ES`) by default
- ElevenLabs descartado (sin créditos free)
