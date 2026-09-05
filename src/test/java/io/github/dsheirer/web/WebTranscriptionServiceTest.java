package io.github.dsheirer.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class WebTranscriptionServiceTest
{
    @Test
    void normalizesScannerNumbersAndPhonetics()
    {
        assertEquals("Unit AB plate 7ABC123", WebTranscriptionService.normalize(
            "Unit Adam Boy plate 7 ABC 123"));
        assertEquals("XY", WebTranscriptionService.normalize("X-ray Yellow"));
    }

    @Test
    void redactsSensitiveInformation()
    {
        String redacted = WebTranscriptionService.redact(
            "DOB 01/02/1980, phone 312-555-1212, SSN 123-45-6789 at 1234 Main Street");
        assertFalse(redacted.contains("1980"));
        assertFalse(redacted.contains("555"));
        assertFalse(redacted.contains("6789"));
        assertFalse(redacted.contains("Main Street"));
    }
}
