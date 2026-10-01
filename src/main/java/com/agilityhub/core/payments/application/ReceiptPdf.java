package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.Money;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/**
 * S12 R-12-27, §10 (E8-T02, T-12-20): the receipt («Rebut») as a synchronous PDF with the club's mark — the band in the club's
 * theme colour with its name, as the list exports draw it (`ListExportRenderer`, which `payments` cannot import: `clubs.common`
 * already depends on it) — in the reader's language, with the descriptions frozen at issue. The payment method shows its
 * masked account only, never an IBAN. Labels are `billing.receipt.*`; the tax's is the club's country profile's
 * (`taxLabelKey`, S12 §10: `ES` → «IVA»).
 */
@Component
public class ReceiptPdf {
    private static final float MARGIN = 48, SIZE = 10, LINE = 15;
    private final IcuMessageSource messages; private final BillingTexts texts;
    public ReceiptPdf(IcuMessageSource messages, BillingTexts texts) { this.messages = messages; this.texts = texts; }

    public byte[] render(Invoice invoice, boolean familyGroup, ClubConfig club, Locale locale) {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
             var fontInput = ReceiptPdf.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDFont font = PDType0Font.load(document, Objects.requireNonNull(fontInput, "PDFBox bundled Liberation Sans"));
            var page = new PDPage(PDRectangle.A4); document.addPage(page);
            float width = page.getMediaBox().getWidth(), y = page.getMediaBox().getHeight();
            var zone = ZoneId.of(club.club().timeZone());
            var dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.setNonStrokingColor(color(club.primaryColor()));
                stream.addRect(0, y - 64, width, 64); stream.fill();
                write(stream, font, 14, MARGIN, y - 40, Color.WHITE, club.club().name());
                y -= 100;
                write(stream, font, 16, MARGIN, y, Color.BLACK, label("title", locale) + " " + invoice.displayNumber()); y -= LINE * 1.6f;
                var rows = new ArrayList<String[]>();
                rows.add(new String[] {label("number", locale), invoice.displayNumber()});
                rows.add(new String[] {label("issueDate", locale), LocalDate.parse(invoice.issueDate()).format(dates)});
                rows.add(new String[] {label("period", locale), texts.month(YearMonth.parse(invoice.period()), locale)});
                var member = invoice.memberSnapshot();
                rows.add(new String[] {label("member", locale), member.fullName() + (member.number() == null ? "" : " (" + member.number() + ")")});
                if (member.taxId() != null) { rows.add(new String[] {label("taxId", locale), member.taxId()}); }
                for (var row : rows) { y = row(stream, font, y, row[0], row[1]); }
                y -= LINE;
                write(stream, font, SIZE, MARGIN, y, Color.DARK_GRAY, label("concept", locale)); y -= LINE;
                for (var line : invoice.lines()) {
                    var wrapped = wrap(font, line.description(), width - 2 * MARGIN - 110);
                    for (int i = 0; i < wrapped.size(); i++) {
                        write(stream, font, SIZE, MARGIN, y, Color.BLACK, wrapped.get(i));
                        if (i == 0) { write(stream, font, SIZE, width - MARGIN - 100, y, Color.BLACK, money(line.total(), locale)); }
                        y -= LINE;
                    }
                }
                y -= LINE / 2;
                if (invoice.tax().amountMinor() != 0) {
                    y = row(stream, font, y, label("base", locale), money(invoice.base(), locale));
                    // S12 §10: the tax is named by the club's country profile (`ES` → «IVA»).
                    String tax = messages.format(club.taxLabelKey(), Map.of(), locale);
                    y = row(stream, font, y, tax, money(invoice.tax(), locale));
                }
                y = row(stream, font, y, label("total", locale), money(invoice.total(), locale));
                y -= LINE;
                var method = invoice.paymentMethod();
                String methodText = messages.format("billing.receipt.method." + method.type().name(), Map.of(), locale)
                        + (method.maskedAccount() == null ? "" : " · " + method.maskedAccount());
                y = row(stream, font, y, label("paymentMethod", locale), methodText);
                y = row(stream, font, y, label("status", locale), messages.format("billing.receipt.status." + invoice.status().name(), Map.of(), locale));
                if (invoice.status() == InvoiceStatus.PAID && invoice.paidAt() != null) {
                    y = row(stream, font, y, label("paidAt", locale), invoice.paidAt().atZone(zone).toLocalDate().format(dates));
                }
                if (familyGroup) { y -= LINE; write(stream, font, SIZE, MARGIN, y, Color.DARK_GRAY, label("familyGroup", locale)); }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private float row(PDPageContentStream stream, PDFont font, float y, String label, String value) throws IOException {
        write(stream, font, SIZE, MARGIN, y, Color.DARK_GRAY, label);
        write(stream, font, SIZE, MARGIN + 150, y, Color.BLACK, value);
        return y - LINE;
    }
    private String label(String key, Locale locale) { return messages.format("billing.receipt." + key, Map.of(), locale); }
    private static String money(Money money, Locale locale) { return money.format(locale); }
    private static void write(PDPageContentStream stream, PDFont font, float size, float x, float y, Color color, String text) throws IOException {
        stream.beginText(); stream.setNonStrokingColor(color); stream.setFont(font, size); stream.newLineAtOffset(x, y);
        stream.showText(printable(font, text)); stream.endText();
    }
    private static List<String> wrap(PDFont font, String value, float width) throws IOException {
        var lines = new ArrayList<String>(); var line = new StringBuilder();
        for (String word : printable(font, value == null ? "" : value).split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (font.getStringWidth(next) * SIZE / 1000 > width && line.length() > 0) { lines.add(line.toString()); line = new StringBuilder(word); }
            else { line = new StringBuilder(next); }
        }
        lines.add(line.toString());
        return lines;
    }
    private static String printable(PDFont font, String value) {
        var text = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            String item = new String(Character.toChars(codePoint));
            try { font.encode(item); text.append(item); } catch (IllegalArgumentException | IOException unsupported) { text.append('?'); }
        });
        return text.toString();
    }
    private static Color color(String hex) {
        try { return Color.decode(hex == null ? "#333333" : hex); } catch (NumberFormatException invalid) { return Color.DARK_GRAY; }
    }
}
