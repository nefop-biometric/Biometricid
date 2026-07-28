# Contrato de API — Onboarding v1

Backend único modular (Spring Boot 3, puerto **8090**). Base path: `/api/v1`.

## Módulos internos (un solo servicio)

```
onboarding-backend
├── catalog        → catálogo de tipos de documento (estático, en código/config)
├── session        → ciclo de vida de la sesión de onboarding (orquestador)
├── ocr            → clasificación de tipo + extracción de campos (Tess4J)
├── authenticity   → antifraude: detección de montaje, recorte, rostro (OpenCV)
├── decision       → consolidación frente/reverso y decisión final
└── persistence    → PostgreSQL (sesiones, capturas, resultados)
```

## Flujo de una sesión

```
POST /sessions            → CREATED
POST /sessions/{id}/documents/FRONT   → FRONT_PROCESSED
POST /sessions/{id}/documents/BACK    → BACK_PROCESSED   (si el tipo tiene 2 caras)
[POST /sessions/{id}/nfc]             → opcional, solo COL_PA
GET  /sessions/{id}       → COMPLETED con decisión: APPROVED | REVIEW | REJECTED
```

Estados de sesión: `CREATED → IN_PROGRESS → COMPLETED | EXPIRED`. La decisión es un atributo del resultado, no un estado de la sesión.

---

## Endpoints

### 1. `GET /api/v1/catalog/documents`

Lista de tipos soportados. El frontend construye el selector de documento con esto (nada de tipos quemados en el HTML, como pasó antes).

```json
[
  {
    "code": "COL_CC_NEW",
    "country": "COL",
    "name": "Cédula digital colombiana",
    "sides": ["FRONT", "BACK"],
    "hasMrz": true,
    "supportsNfc": false,
    "expires": true
  }
]
```

### 2. `POST /api/v1/onboarding/sessions`

Crea una sesión.

**Request**
```json
{
  "documentType": "COL_CC_NEW",
  "metadata": {
    "geolocation": { "lat": 4.65, "lng": -74.05 },
    "userAgent": "..."
  }
}
```

**Response `201`**
```json
{
  "sessionId": "uuid",
  "documentType": "COL_CC_NEW",
  "requiredSides": ["FRONT", "BACK"],
  "status": "CREATED",
  "expiresAt": "2026-07-27T18:30:00Z"
}
```

### 3. `POST /api/v1/onboarding/sessions/{sessionId}/documents/{side}`

`side` = `FRONT` | `BACK`. Multipart: campo `image` (JPEG/PNG). Procesa la cara completa: clasificación → OCR → antifraude. **Síncrono** (igual que el proyecto anterior; el frontend muestra spinner).

**Response `200`**
```json
{
  "side": "FRONT",
  "classification": {
    "detectedType": "COL_CC_NEW",
    "matchesSession": true,
    "confidence": 0.93
  },
  "ocr": {
    "fields": {
      "documentNumber": "1234567890",
      "firstNames": "JUAN CARLOS",
      "lastNames": "PEREZ GOMEZ",
      "birthDate": "1990-05-14",
      "sex": "M",
      "bloodType": "O+"
    },
    "fieldConfidence": { "documentNumber": 0.97, "lastNames": 0.88 },
    "mrz": null
  },
  "authenticity": {
    "score": 0.82,
    "veto": false,
    "checks": [
      { "name": "PHOTO_SUBSTITUTION", "score": 0.85, "passed": true },
      { "name": "FACE_RING_CONSISTENCY", "score": 0.79, "passed": true },
      { "name": "DOCUMENT_CROP_QUALITY", "score": 0.90, "passed": true }
    ]
  },
  "sessionStatus": "IN_PROGRESS"
}
```

Reglas de antifraude heredadas del proyecto anterior: **regla de veto** (score crítico < 0.60 en cards, < 0.35 en pasaportes → `veto: true`, rechazo directo) y **regla de peor cara** (la decisión usa el peor score entre frente y reverso).

**Errores de negocio en esta llamada** (no son 500):
- `422 DOCUMENT_TYPE_MISMATCH` — la imagen no corresponde al tipo de la sesión (incluye `detectedType`).
- `422 IMAGE_QUALITY_TOO_LOW` — borrosa, oscura, documento no detectado en el encuadre.
- `409 SIDE_ALREADY_PROCESSED` — recaptura: se permite reemplazar con `?replace=true`.

### 4. `POST /api/v1/onboarding/sessions/{sessionId}/nfc` *(solo `COL_PA`, opcional)*

El cliente envía los grupos de datos ya leídos del chip. El backend los cruza contra el OCR.

```json
{ "dg1": { "documentNumber": "...", "birthDate": "...", "expiryDate": "..." }, "faceImageBase64": "..." }
```

### 5. `GET /api/v1/onboarding/sessions/{sessionId}`

Estado y resultado consolidado.

**Response `200` (sesión completa)**
```json
{
  "sessionId": "uuid",
  "documentType": "COL_CC_NEW",
  "status": "COMPLETED",
  "sides": { "FRONT": { "...": "resumen por cara" }, "BACK": { "...": "..." } },
  "consolidated": {
    "fields": { "...": "campos fusionados frente+reverso+MRZ" },
    "crossChecks": [
      { "name": "FRONT_BACK_NUMBER_MATCH", "passed": true, "distance": 0 },
      { "name": "MRZ_VIZ_CONSISTENCY", "passed": true },
      { "name": "EXPIRY_VALID", "passed": true }
    ],
    "authenticityScore": 0.78
  },
  "decision": {
    "outcome": "APPROVED",
    "reasons": []
  }
}
```

**Reglas de decisión** (en orden, la primera que aplique gana):
1. Veto antifraude en cualquier cara → `REJECTED` (`reasons: ["AUTHENTICITY_VETO"]`).
2. Tipo detectado ≠ tipo de sesión → `REJECTED`.
3. Documento vencido → `REJECTED`.
4. Incoherencia frente/reverso (Levenshtein > 2) o MRZ contradice zona visual → `REVIEW`.
5. Score de autenticidad en zona gris (0.60–0.70) o confianza OCR baja en campos clave → `REVIEW`.
6. Todo lo demás → `APPROVED`.

---

## Formato de error (todas las respuestas de error)

```json
{
  "timestamp": "2026-07-27T15:00:00Z",
  "status": 422,
  "code": "DOCUMENT_TYPE_MISMATCH",
  "message": "La imagen corresponde a COL_CC_OLD pero la sesión es COL_CC_NEW",
  "sessionId": "uuid",
  "details": { "detectedType": "COL_CC_OLD" }
}
```

## Decisiones técnicas (y por qué)

| Decisión | Razón |
|---|---|
| Un solo backend modular, puerto 8090 | Pedido por Eduin; menos procesos, menos jars bloqueados. Módulos con paquetes separados para poder extraerlos a servicios si algún día hace falta. |
| Procesamiento síncrono por cara | Igual que el proyecto anterior; simple y el frontend ya sabe manejarlo. Tesseract no soporta concurrencia → las llamadas OCR se serializan internamente (semáforo), documentado desde el día 1. |
| Leer `MultipartFile` a `byte[]` al recibirlo | Gotcha conocido: solo se puede leer una vez. |
| Catálogo servido por API | El frontend no quema tipos de documento; agregar un país nuevo no toca el front. |
| PostgreSQL desde v1 | Sesiones y trazabilidad son parte del dominio de onboarding, no un agregado posterior. |
| Imágenes en disco (`captured-documents/{sessionId}/`), no en BD | Igual que antes; la BD guarda rutas y hashes. |
