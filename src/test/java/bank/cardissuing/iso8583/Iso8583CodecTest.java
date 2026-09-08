package bank.cardissuing.iso8583;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Iso8583CodecTest {

    @Test
    void encodeDecode_roundTrips_withPrimaryBitmapOnly() {
        Iso8583Message m = new Iso8583Message("0100")
                .set(2, "4532129876543210").set(3, "000000").set(4, "000000012345").set(7, "0908120000").set(11, "000123")
                .set(22, "051").set(37, "123456789012").set(41, "TERM0001").set(42, "MERCHANT0000001").set(49, "484");
        byte[] wire = Iso8583Codec.encode(m);
        String s = new String(wire, StandardCharsets.US_ASCII);
        assertTrue(s.startsWith("0100"));
        assertEquals(4 + 16, s.indexOf("164532129876543210"), "primary bitmap is 16 hex chars, then LLVAR PAN");
        Iso8583Message back = Iso8583Codec.decode(wire);
        assertEquals("0100", back.getMti());
        assertEquals(m.getFields(), back.getFields());
    }

    @Test
    void secondaryBitmap_appearsOnlyWithFieldsAbove64() {
        Iso8583Message m = new Iso8583Message("0400").set(2, "4532129876543210").set(4, "000000000100").set(11, "000001")
                .set(37, "000000000001").set(90, "0100" + "000001" + "0908120000" + "00000000000" + "00000000000");
        String s = new String(Iso8583Codec.encode(m), StandardCharsets.US_ASCII);
        assertTrue(s.substring(4, 5).matches("[89A-F]"), "first bit set: secondary bitmap present");
        Iso8583Message back = Iso8583Codec.decode(s.getBytes(StandardCharsets.US_ASCII));
        assertEquals(m.get(90), back.get(90));
        assertEquals("0410", back.responseMti());
    }

    @Test
    void reply_echoesIdentifyingFields_andResponseMti() {
        Iso8583Message m = new Iso8583Message("0200").set(2, "4532129876543210").set(4, "000000000100").set(7, "0908120000").set(11, "000007")
                .set(37, "RRN000000001").set(41, "TERM0001").set(52, "0123456789ABCDEF");
        Iso8583Message r = m.reply();
        assertEquals("0210", r.getMti());
        assertEquals("000007", r.get(11));
        assertFalse(r.has(52), "PIN data is never echoed");
        assertTrue(m.describe().contains("453212******3210"));
        assertFalse(m.describe().contains("0123456789ABCDEF"));
    }

    @Test
    void fixedFieldWithWrongLength_isRefused() {
        Iso8583Message m = new Iso8583Message("0800").set(11, "12345");
        assertThrows(IllegalArgumentException.class, () -> Iso8583Codec.encode(m));
    }

    @Test
    void helpers_track2Tlv_approvalId_minorUnits() {
        IsoAuthorizationHandler.Track2 t = IsoAuthorizationHandler.Track2.parse("4532129876543210=2809201173294081");
        assertEquals("4532129876543210", t.pan());
        assertEquals(LocalDate.of(2028, 9, 1), t.expiry());
        assertEquals("201", t.serviceCode());
        assertEquals("1", t.pvki());
        assertEquals("7329", t.pvv());
        assertEquals("408", t.cvv());

        Map<String, String> tlv = IsoAuthorizationHandler.Tlv.parse("9F2608C7925E998F8838769F3602001A9F02060000000012349A03260908");
        assertEquals("C7925E998F883876", tlv.get("9F26"));
        assertEquals("001A", tlv.get("9F36"));
        assertEquals("000000001234", tlv.get("9F02"));
        assertEquals("260908", tlv.get("9A"));
        assertEquals("9108FEBE51BA58C98E80", IsoAuthorizationHandler.Tlv.build("91", "FEBE51BA58C98E80"));
        assertEquals("000000001234260908001A", IsoAuthorizationHandler.Tlv.cdolData(tlv));

        assertEquals("3625AB", IsoAuthorizationHandler.approvalId("AUTH-B328FBCD3625AB"));
        assertEquals(2, IsoAuthorizationHandler.minorUnits("484"));
        assertEquals(0, IsoAuthorizationHandler.minorUnits("392"));
    }
}
