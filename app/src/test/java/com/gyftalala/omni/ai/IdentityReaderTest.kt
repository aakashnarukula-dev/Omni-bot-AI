package com.gyftalala.omni.ai

import com.gyftalala.omni.data.*
import org.junit.Assert.*
import org.junit.Test

class IdentityReaderTest {
    @Test fun panExtractsLabeledFieldsWithoutUsingGovernmentHeader() {
        val text = "INCOME TAX DEPARTMENT\nGOVERNMENT OF INDIA\nName: TEST OWNER\nABCDE1234F\nDOB: 09/08/1990"
        val id = IdentityReader.extract(text, IdKind.PAN)
        assertEquals(IdKind.PAN, IdentityReader.detect(text))
        assertEquals("ABCDE1234F", id.number); assertEquals("TEST OWNER", id.name); assertEquals("09/08/1990", id.birthDate)
    }
    @Test fun aadhaarFrontBackMergesWithoutOverwritingCorrection() {
        val front = IdentityReader.extract("Aadhaar\n2345 6789 0123\nTEST OWNER", IdKind.AADHAAR)
        val back = IdentityReader.extract("Address\n2345 6789 0123", IdKind.AADHAAR, front.copy(name = "CORRECTED OWNER"))
        assertEquals("234567890123", back.number); assertEquals("CORRECTED OWNER", back.name)
        assertFalse(IdentityReader.different("2345 6789 0123", back))
        assertTrue(IdentityReader.different("3456 7890 1234", back))
    }
    @Test fun licenceAndOtherIdUseSeparateFormats() {
        assertEquals("DL1420110012345", IdentityReader.extract("Driving Licence\nDL-14 2011 0012345", IdKind.DL).number)
        assertEquals("EMP-12345", IdentityReader.extract("ID number: EMP-12345", IdKind.OTHER).number)
        assertEquals("", IdentityReader.extract("No legible text", IdKind.OTHER).number)
    }
    @Test fun ambiguousNamesStayEmpty() {
        val id = IdentityReader.extract("GOVERNMENT OF INDIA\nTEST OWNER\nSECOND PERSON\nABCDE1234F", IdKind.PAN)
        assertEquals("", id.name)
    }
    @Test fun missingFieldsDoNotInventData() {
        assertEquals(IdentityDetails(IdKind.PAN), IdentityReader.extract("blurred photo", IdKind.PAN))
        assertNull(IdentityReader.detect("USB Type-C cable"))
    }
    @Test fun stabilityResetsAcrossUnreadableOrDifferentNumbers() {
        val stable = StableIdentityRead()
        assertFalse(stable.accept("ABCDE1234F")); assertFalse(stable.accept(""))
        assertFalse(stable.accept("ABCDE1234F")); assertFalse(stable.accept("ABCDE1234F"))
        assertFalse(stable.accept("FGHIJ5678K")); assertFalse(stable.accept("FGHIJ5678K")); assertTrue(stable.accept("FGHIJ5678K"))
    }
}
