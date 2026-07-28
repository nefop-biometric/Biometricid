package com.eduin.onboarding.catalog;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.eduin.onboarding.catalog.DocumentSide.BACK;
import static com.eduin.onboarding.catalog.DocumentSide.FRONT;

/**
 * Catálogo maestro de tipos de documento. Fuente única de verdad para backend y frontend
 * (el frontend lo consume vía GET /api/v1/catalog/documents).
 * Detalle de campos y validaciones por tipo: docs/01-catalogo-documentos.md
 */
@Component
public class DocumentCatalog {

    private static final List<DocumentSide> BOTH = List.of(FRONT, BACK);
    private static final List<DocumentSide> FRONT_ONLY = List.of(FRONT);

    private final Map<String, DocumentTypeSpec> byCode;

    public DocumentCatalog() {
        List<DocumentTypeSpec> specs = List.of(
                // Colombia
                new DocumentTypeSpec("COL_CC_OLD", "COL", "Cédula de ciudadanía colombiana (amarilla)", BOTH, false, null, false, false),
                new DocumentTypeSpec("COL_CC_NEW", "COL", "Cédula de ciudadanía colombiana (digital/policarbonato)", BOTH, true, "TD1", false, true),
                new DocumentTypeSpec("COL_PA", "COL", "Pasaporte colombiano", FRONT_ONLY, true, "TD3", true, true),
                new DocumentTypeSpec("COL_TI", "COL", "Tarjeta de identidad colombiana (menores de 18)", BOTH, false, null, false, true),
                new DocumentTypeSpec("COL_PPT", "COL", "Permiso por Protección Temporal colombiano", BOTH, false, null, false, true),
                new DocumentTypeSpec("COL_CE", "COL", "Cédula de extranjería colombiana", BOTH, true, "TD1", false, true),
                // España
                new DocumentTypeSpec("ESP_DNI_OLD", "ESP", "DNI español (versión antigua)", BOTH, true, "TD1", false, true),
                new DocumentTypeSpec("ESP_DNI_NEW", "ESP", "DNI español (versión nueva, formato UE)", BOTH, true, "TD1", false, true),
                // Ecuador
                new DocumentTypeSpec("ECU_DNI_OLD", "ECU", "Cédula de identidad ecuatoriana (versión antigua)", BOTH, false, null, false, true),
                new DocumentTypeSpec("ECU_DNI_NEW", "ECU", "Cédula de identidad ecuatoriana (versión nueva)", BOTH, false, null, false, true),
                // Perú
                new DocumentTypeSpec("PER_DNI_OLD", "PER", "DNI peruano (azul)", BOTH, false, null, false, true),
                new DocumentTypeSpec("PER_DNI_NEW", "PER", "DNI electrónico peruano (DNIe)", BOTH, true, "TD1", false, true),
                // Panamá
                new DocumentTypeSpec("PAN_DNI_OLD", "PAN", "Cédula de identidad panameña (versión antigua)", BOTH, false, null, false, true),
                new DocumentTypeSpec("PAN_DNI_NEW", "PAN", "Cédula de identidad panameña (versión nueva)", BOTH, false, null, false, true));

        this.byCode = specs.stream()
                .collect(Collectors.toUnmodifiableMap(DocumentTypeSpec::code, Function.identity()));
    }

    public List<DocumentTypeSpec> all() {
        return byCode.values().stream()
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .toList();
    }

    public Optional<DocumentTypeSpec> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }
}
