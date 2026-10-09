package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.domain.CountryProfile;
import com.agilityhub.core.platform.domain.Theme;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.Money;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link ReceiptPdf} (S12 R-12-27, §10; T-12-20). The receipt's content stream is read back with
 * PDFBox: every text block with its decoded text, position, size and fill colour, and the filled rectangles. This pins the
 * club band in the theme colour (with its fallbacks), the label/value rows, the wrapped description with its amount on its
 * first line only, the paid date of a paid receipt only and the family-group note only for the holder's receipts.
 */
class ReceiptPdfSurvivorsTest {
    static final String CLUB = "club-a";
    static final Locale EN = Locale.ENGLISH;
    static final float WIDTH = PDRectangle.A4.getWidth(), HEIGHT = PDRectangle.A4.getHeight();
    /** The description column: the page minus both margins (48) minus the amount column (110). */
    static final float AVAILABLE = WIDTH - 2 * 48 - 110;
    static final Instant NOW = Instant.parse("2026-09-01T06:00:00Z");
    static final Instant PAID_AT = Instant.parse("2026-09-03T10:00:00Z");

    final IcuMessageSource messages = mock(IcuMessageSource.class);
    final BillingTexts texts = mock(BillingTexts.class);
    final ReceiptPdf pdf = new ReceiptPdf(messages, texts);

    @BeforeEach void stubs() {
        when(messages.format(anyString(), anyMap(), any(Locale.class))).thenAnswer(call -> "[" + call.getArgument(0) + "]");
        when(texts.month(any(YearMonth.class), any(Locale.class))).thenReturn("September 2026");
    }

    @Test void T_12_20_aPaidHoldersReceiptDrawsTheClubBandAndEveryRowAtItsPlace() throws IOException {
        String first = firstLine(), second = "September-2026";
        // Fixture: one wrap at the column width, none with a wider column (the line-64 and line-110 mutants).
        assertThat(width(first)).isLessThan(AVAILABLE);
        assertThat(width(first + " " + second)).isGreaterThan(AVAILABLE).isLessThan(AVAILABLE + 192);
        var invoice = invoice(InvoiceStatus.PAID, PAID_AT, new Invoice.MemberSnapshot(42, "Laura Serra", "00000000T"), 1822,
                List.of(line(1, first + " " + second, 9000), line(2, "Single class", 1500)));

        var page = read(pdf.render(invoice, true, config("#1E88E5"), EN));

        assertThat(page.fills()).containsExactly(fill(0, HEIGHT - 64, WIDTH, 64, new Color(0x1E, 0x88, 0xE5)));
        var expected = new ArrayList<String>();
        expected.add(text("Example Agility Club", 48, HEIGHT - 40, 14, Color.WHITE));
        float y = HEIGHT - 100;
        expected.add(text("[billing.receipt.title] 2026-0042", 48, y, 16, Color.BLACK));
        y -= 15 * 1.6f;
        y = row(expected, y, "[billing.receipt.number]", "2026-0042");
        y = row(expected, y, "[billing.receipt.issueDate]", date(LocalDate.of(2026, 9, 1)));
        y = row(expected, y, "[billing.receipt.period]", "September 2026");
        y = row(expected, y, "[billing.receipt.member]", "Laura Serra (42)");
        y = row(expected, y, "[billing.receipt.taxId]", "00000000T");
        y -= 15;
        expected.add(text("[billing.receipt.concept]", 48, y, 10, Color.DARK_GRAY));
        y -= 15;
        // A wrapped description carries its amount on its first line only, in the right-hand column.
        expected.add(text(first, 48, y, 10, Color.BLACK));
        expected.add(text(money(9000), WIDTH - 48 - 100, y, 10, Color.BLACK));
        y -= 15;
        expected.add(text(second, 48, y, 10, Color.BLACK));
        y -= 15;
        expected.add(text("Single class", 48, y, 10, Color.BLACK));
        expected.add(text(money(1500), WIDTH - 48 - 100, y, 10, Color.BLACK));
        y -= 15;
        y -= 7.5f;
        y = row(expected, y, "[billing.receipt.base]", money(8678));
        y = row(expected, y, "[billing.receipt.tax.ES]", money(1822));
        y = row(expected, y, "[billing.receipt.total]", money(10500));
        y -= 15;
        y = row(expected, y, "[billing.receipt.paymentMethod]", "[billing.receipt.method.SEPA_DD]");
        y = row(expected, y, "[billing.receipt.status]", "[billing.receipt.status.PAID]");
        y = row(expected, y, "[billing.receipt.paidAt]", date(LocalDate.of(2026, 9, 3)));
        y -= 15;
        expected.add(text("[billing.receipt.familyGroup]", 48, y, 10, Color.DARK_GRAY));
        assertThat(page.texts()).containsExactlyElementsOf(expected);
    }

    @Test void T_12_20_aReturnedReceiptWithoutNumberTaxIdOrThemeColourShowsNeitherPaidDateNorFamilyNote() throws IOException {
        // A returned debit (FAILED) keeps its paidAt, but only a PAID receipt shows it.
        var invoice = invoice(InvoiceStatus.FAILED, PAID_AT, new Invoice.MemberSnapshot(null, "Laura Serra", null), 0,
                List.of(line(1, "Single class", 1500)));

        var page = read(pdf.render(invoice, false, config(null), EN));

        assertThat(page.fills()).containsExactly(fill(0, HEIGHT - 64, WIDTH, 64, new Color(0x33, 0x33, 0x33)));
        var strings = page.blocks().stream().map(Block::text).toList();
        assertThat(strings).contains("Laura Serra", "[billing.receipt.status.FAILED]")
                .doesNotContain("[billing.receipt.paidAt]", "[billing.receipt.familyGroup]", "[billing.receipt.taxId]", "[billing.receipt.base]");
        assertThat(strings).noneMatch(text -> text.contains("null"));
    }

    @Test void T_12_20_anUnreadableThemeColourFallsBackToDarkGray() throws IOException {
        var invoice = invoice(InvoiceStatus.PAID, PAID_AT, new Invoice.MemberSnapshot(42, "Laura Serra", null), 0, List.of(line(1, "Single class", 1500)));

        var page = read(pdf.render(invoice, false, config("not-a-colour"), EN));

        assertThat(page.fills()).containsExactly(fill(0, HEIGHT - 64, WIDTH, 64, Color.DARK_GRAY));
    }

    @Test void T_12_20_aSingleWordWiderThanTheColumnStaysOnOneLineWithItsAmount() throws IOException {
        var word = new StringBuilder("X");
        while (width(word.toString()) <= AVAILABLE + 20) { word.append('X'); }
        var invoice = invoice(InvoiceStatus.PAID, PAID_AT, new Invoice.MemberSnapshot(42, "Laura Serra", null), 0, List.of(line(1, word.toString(), 9000)));

        var page = read(pdf.render(invoice, false, config("#1E88E5"), EN));

        // No empty first line: the word is the line, and the amount is on it.
        assertThat(page.blocks()).noneMatch(block -> block.text().isEmpty());
        var description = page.blocks().stream().filter(block -> block.text().equals(word.toString())).findFirst().orElseThrow();
        var amount = page.blocks().stream().filter(block -> block.text().equals(money(9000))).findFirst().orElseThrow();
        assertThat(amount.y()).isEqualTo(description.y());
    }

    // --- content stream reader ----------------------------------------------------------------------------------------------

    record Block(String text, float x, float y, float size, float[] rgb) {
        String describe() { return format(text, x, y, size, rgb); }
    }
    record Page(List<Block> blocks, List<String> fills) {
        List<String> texts() { return blocks.stream().map(Block::describe).toList(); }
    }

    /** Every `Tj` with the text state and fill colour in force, and every filled `re`; Type0 codes decoded by the page's font. */
    static Page read(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            var page = document.getPage(0);
            var blocks = new ArrayList<Block>();
            var fills = new ArrayList<String>();
            var operands = new ArrayList<Object>();
            float[] color = {0, 0, 0};
            float[] rect = null;
            PDFont font = null;
            float size = 0, x = 0, y = 0;
            var parser = new PDFStreamParser(page);
            for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
                if (!(token instanceof Operator operator)) { operands.add(token); continue; }
                switch (operator.getName()) {
                    case "rg", "sc", "scn" -> color = numbers(operands);
                    case "g" -> { float gray = numbers(operands)[0]; color = new float[] {gray, gray, gray}; }
                    case "re" -> rect = numbers(operands);
                    case "f" -> fills.add(rect == null ? "fill without rectangle" : fill(rect[0], rect[1], rect[2], rect[3], color));
                    case "Tf" -> { font = page.getResources().getFont((COSName) operands.get(0)); size = ((COSNumber) operands.get(1)).floatValue(); }
                    case "Td" -> { var offset = numbers(operands); x = offset[0]; y = offset[1]; }
                    case "Tj" -> blocks.add(new Block(decode(font, (COSString) operands.get(0)), x, y, size, color));
                    default -> { }
                }
                operands.clear();
            }
            return new Page(blocks, fills);
        }
    }

    static float[] numbers(List<Object> operands) {
        var numbers = operands.stream().filter(COSNumber.class::isInstance).map(COSNumber.class::cast).toList();
        float[] values = new float[numbers.size()];
        for (int i = 0; i < values.length; i++) { values[i] = numbers.get(i).floatValue(); }
        return values;
    }

    static String decode(PDFont font, COSString string) throws IOException {
        var input = new ByteArrayInputStream(string.getBytes());
        var text = new StringBuilder();
        while (input.available() > 0) {
            String unicode = font.toUnicode(font.readCode(input));
            text.append(unicode == null ? "�" : unicode);
        }
        return text.toString().replace(' ', ' ').replace(' ', ' ');
    }

    static String format(String text, float x, float y, float size, float[] rgb) {
        return String.format(Locale.ROOT, "%s @ %.1f,%.1f size %.0f rgb %.3f/%.3f/%.3f", text, x, y, size, rgb[0], rgb[1], rgb[2]);
    }
    static String text(String text, float x, float y, float size, Color color) {
        return format(text, x, y, size, components(color));
    }
    static String fill(float x, float y, float w, float h, float[] rgb) {
        return String.format(Locale.ROOT, "fill %.1f,%.1f %.1fx%.1f rgb %.3f/%.3f/%.3f", x, y, w, h, rgb[0], rgb[1], rgb[2]);
    }
    static String fill(float x, float y, float w, float h, Color color) { return fill(x, y, w, h, components(color)); }
    static float[] components(Color color) { return new float[] {color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f}; }

    /** A label (dark grey, left) and its value (black, 150 pt further) on one row; the next row is 15 pt lower. */
    static float row(List<String> expected, float y, String label, String value) {
        expected.add(text(label, 48, y, 10, Color.DARK_GRAY));
        expected.add(text(value, 48 + 150, y, 10, Color.BLACK));
        return y - 15;
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static float width(String text) throws IOException {
        try (var document = new PDDocument();
             var input = ReceiptPdf.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return PDType0Font.load(document, input).getStringWidth(text) * 10 / 1000;
        }
    }
    /** «Monthly agility agility …» just under the column width minus 20 pt. */
    static String firstLine() throws IOException {
        var line = new StringBuilder("Monthly");
        while (width(line + " agility") <= AVAILABLE - 20) { line.append(" agility"); }
        return line.toString();
    }

    static String date(LocalDate date) { return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(EN)); }
    static String money(long amount) { return eur(amount).format(EN).replace(' ', ' ').replace(' ', ' '); }
    static Money eur(long amount) { return new Money(amount, "EUR"); }

    static Invoice.Line line(int number, String description, long total) {
        return new Invoice.Line(number, InvoiceLineOrigin.MONTHLY_FEE, "price-1", null, description, eur(total), BigDecimal.ZERO, eur(0), eur(total));
    }

    static Invoice invoice(InvoiceStatus status, Instant paidAt, Invoice.MemberSnapshot member, long tax, List<Invoice.Line> lines) {
        var method = new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, null, "Laura Serra", "MANDATE-0001", null, null);
        return new Invoice("invoice-1", CLUB, "2026", 42, "2026-0042", "2026-09-01", "2026-09", "member-1", member, lines,
                eur(10500 - tax), eur(tax), eur(10500), method, status, InvoiceKind.PERIODIC, "run-1", null, false, null,
                paidAt, null, null, null, null, null, Map.of(), 1L, NOW, null, NOW, null);
    }

    static ClubConfig config(String primary) {
        var colors = new Theme.Colors(primary, "#FFFFFF", "#FFFFFF", "#FFFFFF", "#F5F5F5", "#111111", "#666666", "#DDDDDD",
                "#2E7D32", "#ED6C02", "#D32F2F", "#0288D1");
        var theme = new Theme(null, null, null, colors, "Inter", "8px", List.of(), Theme.Mode.light);
        var club = new ClubConfig.ClubView(CLUB, "example-club", "Example Agility Club", List.of("en"), "en", "Europe/Madrid", "EUR",
                theme, null, "ACTIVE", null);
        var profile = mock(CountryProfile.class);
        when(profile.taxLabelKey()).thenReturn("billing.receipt.tax.ES");
        return new ClubConfig(club, Map.of(), Set.of(Module.BILLING), profile, Map.of());
    }
}
