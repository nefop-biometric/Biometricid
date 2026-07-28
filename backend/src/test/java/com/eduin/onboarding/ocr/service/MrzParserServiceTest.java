package com.eduin.onboarding.ocr.service;

import com.eduin.onboarding.ocr.model.MrzData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MrzParserServiceTest {

    private final MrzParserService parser = new MrzParserService();

    @Test
    void parseTD3Passport() {
        // Pasaporte de prueba ICAO 9303 (datos ficticios)
        // L2: docNum(9)+check(1)+nat(3)+dob(6)+check(1)+sexo(1)+expiry(6)+check(1)+opcional(14)+check(1)+composite(1)
        String mrzText = "P<COLMARTINEZ<<JUAN<<<<<<<<<<<<<<<<<<<<<<<<<\n" +
                         "AB12345671COL8001014M3001019<<<<<<<<<<<<<<<0";

        MrzData data = parser.parse(mrzText);

        assertThat(data).isNotNull();
        assertThat(data.getMrzType()).isEqualTo("TD3");
        assertThat(data.getSurnames()).isEqualTo("MARTINEZ");
        assertThat(data.getGivenNames()).isEqualTo("JUAN");
        assertThat(data.getIssuingCountry()).isEqualTo("COL");
        assertThat(data.getSex()).isEqualTo("M");
    }

    @Test
    void parseTD1CedulaConOcrDegradado() {
        // Reverso de cédula colombiana nueva: el OCR confunde '<' con K/X
        // y lee 'I' como '1'. El parser debe normalizar y extraer los nombres.
        String mrzText = "1CC0L004266659815001xxxxxxxx\n" +
                         "7907212M3209239C0L80009938KKK4\n" +
                         "ORDONEZKPARRAKKEDUINKFABIANKK";

        MrzData data = parser.parse(mrzText);

        assertThat(data).isNotNull();
        assertThat(data.getMrzType()).isEqualTo("TD1");
        assertThat(data.getIssuingCountry()).isEqualTo("COL");
        assertThat(data.getDateOfBirth()).isEqualTo("790721");
        assertThat(data.isDateOfBirthValid()).isTrue();
        assertThat(data.getSex()).isEqualTo("M");
        assertThat(data.getExpiryDate()).isEqualTo("320923");
        assertThat(data.isExpiryDateValid()).isTrue();
        assertThat(data.getSurnames()).isEqualTo("ORDONEZ PARRA");
        assertThat(data.getGivenNames()).isEqualTo("EDUIN FABIAN");
        // Cédula COL: documentNumber = NUIP del campo opcional L2 (80009938),
        // NO el serial de tarjeta de la línea 1 (004266659)
        assertThat(data.getDocumentNumber()).isEqualTo("80009938");
    }

    @Test
    void rechazaTextoNormalComoMrz() {
        // Texto de un documento que al quitar espacios tiene longitud de línea MRZ:
        // NO debe interpretarse como MRZ (sin señal estructural).
        String texto = "Ss. Cod. pais / Country code Pasaporte N / Passport No\n" +
                       "REPUBLICA DE COLOMBIA PASAPORTE PASSPORT COLOMBIA";

        assertThat(parser.parse(texto)).isNull();
    }

    @Test
    void checkDigitValidation() {
        // Dígito verificador ICAO de "AB1234567":
        // A(10)*7 + B(11)*3 + 1*1 + 2*7 + 3*3 + 4*1 + 5*7 + 6*3 + 7*1 = 191 → 191 % 10 = 1
        assertThat(parser.verifyCheckDigit("AB1234567", "1")).isTrue();
        assertThat(parser.verifyCheckDigit("AB1234567", "7")).isFalse();
    }
}
