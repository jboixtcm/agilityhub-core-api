package com.agilityhub.core.payments.application.sepa;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.w3c.dom.Element;

/** A spreadsheet view of the immutable debit instructions, including the debtor at issue time. */
public final class RemittanceWorkbook {
    private RemittanceWorkbook() {}

    public static byte[] render(InputStream xml, MessageSource messages, Locale locale) {
        var parser = DocumentBuilderFactory.newDefaultInstance();
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            parser.setNamespaceAware(true);
            parser.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = parser.newDocumentBuilder().parse(xml);
            var sheet = workbook.createSheet(messages.getMessage("billing.bank.sheet", null, locale));
            var title = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            title.setFont(font);
            var header = sheet.createRow(0);
            var columns = List.of("reference","holder","iban","mandate","date","description","amount","currency");
            int[] widths = {25,32,32,38,16,60,16,12};
            for (int i = 0; i < columns.size(); i++) {
                var cell = header.createCell(i);
                cell.setCellValue(messages.getMessage("billing.bank." + columns.get(i), null, locale));
                cell.setCellStyle(title);
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            var moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            sheet.createFreezePane(0,1);
            var blocks = document.getElementsByTagNameNS("*","PmtInf");
            int rowIndex = 1;
            for (int i = 0; i < blocks.getLength(); i++) {
                var block = (Element) blocks.item(i);
                var due = LocalDate.parse(text(block,"ReqdColltnDt"));
                var debits = block.getElementsByTagNameNS("*","DrctDbtTxInf");
                for (int j = 0; j < debits.getLength(); j++) {
                    var debit = (Element) debits.item(j);
                    var row = sheet.createRow(rowIndex++);
                    row.createCell(0).setCellValue(text(debit,"EndToEndId"));
                    row.createCell(1).setCellValue(text(child(debit,"Dbtr"),"Nm"));
                    row.createCell(2).setCellValue(text(child(debit,"DbtrAcct"),"IBAN"));
                    row.createCell(3).setCellValue(text(debit,"MndtId"));
                    row.createCell(4).setCellValue(due);
                    row.getCell(4).setCellStyle(dateStyle);
                    row.createCell(5).setCellValue(text(debit,"Ustrd"));
                    var amount = child(debit,"InstdAmt");
                    row.createCell(6).setCellValue(Double.parseDouble(amount.getTextContent()));
                    row.getCell(6).setCellStyle(moneyStyle);
                    row.createCell(7).setCellValue(amount.getAttribute("Ccy"));
                }
            }
            workbook.write(bytes);
            return bytes.toByteArray();
        } catch (java.io.IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException failure) {
            throw new IllegalStateException("Cannot read the stored remittance or render its workbook", failure);
        }
    }

    private static Element child(Element parent, String name) {
        return (Element) parent.getElementsByTagNameNS("*", name).item(0);
    }
    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent();
    }
}
