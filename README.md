# Onboarding — Verificación de documentos de identidad

Sistema de onboarding con documentos de identidad de 5 países (Colombia, España, Ecuador, Perú, Panamá). Proyecto nuevo, iniciado 2026-07-27, reemplaza el enfoque de 3 servicios del proyecto anterior por **un backend modular único**.

## Estructura

```
onboarding/
├── docs/
│   ├── 01-catalogo-documentos.md   ← 14 tipos de documento, campos y validaciones
│   └── 02-contrato-api.md          ← endpoints, flujo de sesión, reglas de decisión
├── backend/                        ← Spring Boot 3 / Java 17 (puerto 8090)
└── frontend/                       ← HTML sin build (puerto 5500)            [pendiente]
```

## Build y arranque del backend

```bash
cd backend
C:/Users/GSE/Maven/bin/mvn -q package -Dmaven.test.skip=true
"/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot/bin/java" -jar target/onboarding-backend-1.0.0.jar
```

> ⚠️ El `java` del PATH es Java 8 — arrancar SIEMPRE con el JDK 17 de `JAVA_HOME` (ruta de arriba).
> ⚠️ Matar el proceso java antes de recompilar (jar bloqueado en Windows).

- Swagger UI: http://localhost:8090/swagger-ui.html
- Health: http://localhost:8090/actuator/health
- BD: `onboarding_db` en PostgreSQL 16 local (postgres/postgres). El esquema lo gobierna Flyway (`backend/src/main/resources/db/migration/`); Hibernate solo valida (`ddl-auto=validate`).
- Imágenes capturadas: `backend/captured-documents/{sessionId}/{FRONT|BACK}.jpg`

## Stack

- **Backend**: Spring Boot 3.2, Java 17, Tess4J (OCR), OpenCV/bytedeco (antifraude), PostgreSQL.
- **Frontend**: `index.html` único sin build, servido con `npx http-server -p 5500 -c-1`.
- **Build**: Maven (`C:\Users\GSE\Maven\bin\mvn`), JDK 17 vía `JAVA_HOME`.

## Estado

- [x] Catálogo de documentos definido (pendiente validar campos "por confirmar" con imágenes reales)
- [x] Contrato de API definido
- [x] Esqueleto del backend (Flyway + Swagger + manejo de errores del contrato)
- [x] Módulo catalog + session (flujo completo probado con curl: crear sesión, subir caras, recaptura, casos de error)
- [x] Módulo OCR — portado del proyecto anterior (14 extractores, MRZ TD1/TD3 con gating reforzado por plausibilidad de fechas, PDF417/QR, clasificador). Requiere Tesseract instalado en `C:\Program Files\Tesseract-OCR` (tessdata: spa, cat, eng, mrz). Probado con imágenes reales COL_CC_NEW y ESP_DNI_OLD.
- [x] Módulo authenticity — portado de true-document-backend: recorte del documento, detección de recaptura/fotocopia/manipulación (rostro Haar + anillo), estructura, MRZ de pasaporte. Regla de veto por cara (crítico <0.60 cards / <0.35 pasaportes).
- [x] Módulo decision — consolidación de campos por confianza, cross-checks (Levenshtein frente/reverso ≤2, vencimiento), regla de peor cara (0.55/0.45 con arrastre) y decisión APPROVED/REVIEW/REJECTED con razones.
- [ ] Endpoint NFC para COL_PA
- [ ] Frontend
