# Catálogo de documentos — Onboarding v1

Catálogo maestro de los tipos de documento soportados. El código de tipo (`documentType`) es el identificador que usan el frontend, la API y los extractores OCR.

## Convención de códigos

`{PAIS}_{TIPO}_{VERSION}` — país en ISO alpha-3, tipo según el documento, versión `OLD`/`NEW` cuando aplica.

## Tabla maestra

| Código | País | Documento | Caras | MRZ | Vence |
|---|---|---|---|---|---|
| `COL_CC_OLD` | Colombia | Cédula de ciudadanía amarilla (hologramas) | Frente + reverso | No (PDF417 en reverso) | No |
| `COL_CC_NEW` | Colombia | Cédula digital / policarbonato | Frente + reverso | Sí — TD1 (reverso) | Sí |
| `COL_PA` | Colombia | Pasaporte (con o sin chip NFC) | Página de datos | Sí — TD3 | Sí |
| `COL_TI` | Colombia | Tarjeta de identidad (menores de 18) | Frente + reverso | Por confirmar según versión | Sí |
| `COL_PPT` | Colombia | Permiso por Protección Temporal (migrantes venezolanos) | Frente + reverso | No | Sí |
| `COL_CE` | Colombia | Cédula de extranjería | Frente + reverso | Sí — TD1 (versión vigente) | Sí |
| `ESP_DNI_OLD` | España | DNI versión antigua | Frente + reverso | Sí — TD1 (reverso) | Sí |
| `ESP_DNI_NEW` | España | DNI versión nueva (formato UE) | Frente + reverso | Sí — TD1 (reverso) | Sí |
| `ECU_DNI_OLD` | Ecuador | Cédula de identidad versión antigua | Frente + reverso | No | Sí |
| `ECU_DNI_NEW` | Ecuador | Cédula de identidad versión nueva | Frente + reverso | Por confirmar | Sí |
| `PER_DNI_OLD` | Perú | DNI azul | Frente + reverso | No | Sí |
| `PER_DNI_NEW` | Perú | DNI electrónico (DNIe) | Frente + reverso | Sí — TD1 (reverso) | Sí |
| `PAN_DNI_OLD` | Panamá | Cédula de identidad versión antigua | Frente + reverso | No | Sí |
| `PAN_DNI_NEW` | Panamá | Cédula de identidad versión nueva | Frente + reverso | Por confirmar | Sí |

> Los "Por confirmar" se resuelven al calibrar cada plantilla con imágenes reales de prueba. La estructura del catálogo ya los soporta (campo `mrz.format` nullable).

## Campos extraídos

### Campos comunes (todos los documentos)

| Campo | Tipo | Notas |
|---|---|---|
| `documentNumber` | string | Número principal del documento |
| `firstNames` | string | Nombres |
| `lastNames` | string | Apellidos |
| `birthDate` | date (ISO 8601) | Fecha de nacimiento |
| `sex` | `M` / `F` / `X` | |
| `nationality` | ISO alpha-3 | |
| `expiryDate` | date, nullable | Null en `COL_CC_OLD` (no vence) |
| `issueDate` | date, nullable | Fecha de expedición si el documento la trae |

### Campos específicos por documento

- **`COL_CC_OLD` / `COL_CC_NEW` / `COL_TI`**: `bloodType` (RH), `birthPlace`, `issuePlace`, `height` (solo OLD).
- **`COL_PA`**: `passportNumber` (= documentNumber), `mrz` completa, `nfc` (opcional: datos del chip si el cliente los envía).
- **`COL_PPT`**: `pptNumber` (= documentNumber), `rumvNumber` (si es legible).
- **`COL_CE`**: `bloodType`, `visaCategory` (si es legible).
- **`ESP_DNI_*`**: `supportNumber` (número de soporte), `dniLetter` (letra de control validable algorítmicamente), `can` (solo NEW, si es legible).
- **`ECU_DNI_*`**: `civilStatus` (estado civil), `birthPlace`.
- **`PER_DNI_*`**: `checkDigit` (dígito verificador validable), `civilStatus`, `ubigeo` (solo OLD).
- **`PAN_DNI_*`**: `birthPlace`. El número tiene formato provincia-tomo-asiento (ej. `8-123-4567`), validable por patrón.

### Objeto MRZ (cuando el documento la tiene)

```json
{
  "raw": ["línea1", "línea2", "línea3"],
  "format": "TD1 | TD3",
  "checkDigits": { "total": 3, "valid": 2 },
  "fields": { "documentNumber": "...", "birthDate": "...", "expiryDate": "...", "..." : "..." }
}
```

**Regla de gating MRZ** (heredada del proyecto anterior, funcionó bien): con normalización OCR previa (`<`↔K/X/S/C, O↔0, I↔1):
- 0/3 dígitos verificadores válidos → descartar la MRZ (no usarla para enriquecer).
- 2+/3 válidos → usar la MRZ para enriquecer/corregir los campos de la zona visual.

## Validaciones por tipo (además del OCR)

| Validación | Aplica a |
|---|---|
| Letra de control del DNI (mod-23) | `ESP_DNI_*` |
| Dígito verificador del número | `PER_DNI_*`, cédula ecuatoriana (mod-10) |
| Patrón del número (regex por tipo) | Todos |
| Coherencia frente/reverso (Levenshtein ≤ 2 en número y apellidos) | Todos los de 2 caras |
| Documento vencido (`expiryDate < hoy`) | Todos los que vencen |
| Checksums MRZ | Todos los que tienen MRZ |
