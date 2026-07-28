package com.eduin.onboarding.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catálogo", description = "Tipos de documento soportados")
public class CatalogController {

    private final DocumentCatalog catalog;

    public CatalogController(DocumentCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/documents")
    @Operation(summary = "Lista los tipos de documento soportados")
    public List<DocumentTypeSpec> documents() {
        return catalog.all();
    }
}
