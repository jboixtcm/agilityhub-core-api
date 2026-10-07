package com.agilityhub.core.payments.application.sepa;

import com.agilityhub.core.payments.domain.SepaDirectDebits;
import com.agilityhub.core.payments.domain.SepaText;
import com.agilityhub.core.payments.sepa.generated.*;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import javax.xml.XMLConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * S12 R-12-12 (E8-T03, ADR-006): a {@link SepaDirectDebits.File} as a pain.008 `CstmrDrctDbtInitn` document, through the JAXB
 * classes xjc generates from the schema in use ({@value #RESOURCE}, `billing.sepa.schema`), and the validation of the written
 * bytes against that schema.
 *
 * <p>The output is deterministic, so a golden file can pin it (T-12-11): UTF-8, a fixed declaration without `standalone`, the
 * default namespace, JAXB's element order (the schema's), four-space indentation, `\n` line ends and a final newline;
 * `CreDtTm` is the club-local time to the second, without offset. Free texts go through {@link SepaText}. A mandatory agent
 * without a BIC is `Othr/Id = NOTPROVIDED` (EPC IG, IBAN-only).
 */
public final class Pain008Document {
    /** The only value of `billing.sepa.schema` the catalog allows today (`.08` would add its own schema file and classes). */
    public static final String SCHEMA = "pain.008.001.02";
    static final String RESOURCE = "sepa/" + SCHEMA + ".xsd";
    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** The file failed the schema: the position only, never the offending value (it may be an IBAN). */
    public static final class InvalidDocument extends RuntimeException {
        private final int line, column;
        InvalidDocument(int line, int column) { super("pain.008 failed its schema at line " + line + ", column " + column); this.line = line; this.column = column; }
        public int line() { return line; }
        public int column() { return column; }
    }

    private static final class Holder {
        static final JAXBContext CONTEXT;
        static final Schema XSD;
        static final DatatypeFactory DATES = DatatypeFactory.newDefaultInstance();
        static {
            try {
                CONTEXT = JAXBContext.newInstance(ObjectFactory.class);
                var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                XSD = factory.newSchema(Pain008Document.class.getClassLoader().getResource(RESOURCE));
            } catch (JAXBException | SAXException failure) { throw new IllegalStateException("The pain.008 schema " + RESOURCE + " cannot be loaded", failure); }
        }
    }
    private Pain008Document() { }

    /** The document's bytes (not yet validated: see {@link #validate}). */
    public static byte[] write(SepaDirectDebits.File file) {
        identifier("MsgId", file.messageId());
        for (var block : file.blocks()) {
            identifier("PmtInfId", block.paymentInformationId());
            for (var debit : block.debits()) {
                identifier("EndToEndId", debit.endToEndId());
                identifier("MndtId", debit.mandateRef());
            }
        }
        var factory = new ObjectFactory();
        var header = factory.createGroupHeaderSDD();
        header.setMsgId(file.messageId());
        header.setCreDtTm(Holder.DATES.newXMLGregorianCalendar(DATE_TIME.format(file.createdAt())));
        header.setNbOfTxs(Integer.toString(file.count()));
        header.setCtrlSum(file.total());
        var initiator = factory.createPartyIdentificationSEPA1();
        initiator.setNm(SepaText.of(file.creditor().name(), SepaText.NAME));
        var initiatorId = factory.createPartySEPAChoice();
        var organisation = factory.createOrganisationIdentificationSEPAChoice();
        var other = factory.createGenericOrganisationIdentification1();
        other.setId(file.creditor().identifier());
        organisation.setOthr(other); initiatorId.setOrgId(organisation); initiator.setId(initiatorId);
        header.setInitgPty(initiator);
        var initiation = factory.createCustomerDirectDebitInitiationV02();
        initiation.setGrpHdr(header);
        for (var block : file.blocks()) { initiation.getPmtInf().add(block(factory, file, block)); }
        var document = factory.createDocument();
        document.setCstmrDrctDbtInitn(initiation);
        try {
            var marshaller = Holder.CONTEXT.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
            var out = new ByteArrayOutputStream();
            marshaller.marshal(factory.createDocument(document), out);
            String body = out.toString(StandardCharsets.UTF_8).strip();
            return (DECLARATION + body + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JAXBException failure) { throw new IllegalStateException("The pain.008 document cannot be marshalled", failure); }
    }

    private static void identifier(String field, String value) {
        if (value == null || value.isBlank() || value.length() > SepaDirectDebits.IDENTIFIER
                || !com.agilityhub.core.shared.domain.SepaCharacters.containsOnly(value)) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.SEPA_NOT_CONFIGURED,
                    java.util.Map.of("reason", "IDENTIFIER", "field", field));
        }
    }

    /** R-12-11: {@code xml} against the schema in use; {@link InvalidDocument} (line and column only) when it fails. */
    public static void validate(byte[] xml) {
        try {
            var validator = Holder.XSD.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (SAXParseException invalid) { throw new InvalidDocument(invalid.getLineNumber(), invalid.getColumnNumber()); }
        catch (SAXException invalid) { throw new InvalidDocument(-1, -1); }
        catch (IOException unreadable) { throw new UncheckedIOException(unreadable); }
    }

    private static PaymentInstructionInformationSDD block(ObjectFactory factory, SepaDirectDebits.File file, SepaDirectDebits.Block block) {
        var creditor = file.creditor();
        var payment = factory.createPaymentInstructionInformationSDD();
        payment.setPmtInfId(block.paymentInformationId());
        payment.setPmtMtd(PaymentMethod2Code.DD);
        payment.setNbOfTxs(Integer.toString(block.debits().size()));
        payment.setCtrlSum(block.total());
        var type = factory.createPaymentTypeInformationSDD();
        var level = factory.createServiceLevel(); level.setCd("SEPA"); type.setSvcLvl(level);
        var instrument = factory.createLocalInstrumentSEPA(); instrument.setCd("CORE"); type.setLclInstrm(instrument);
        type.setSeqTp(SequenceType1Code.valueOf(block.sequence().name()));
        payment.setPmtTpInf(type);
        payment.setReqdColltnDt(date(file.collectionDate()));
        var name = factory.createPartyIdentificationSEPA5(); name.setNm(SepaText.of(creditor.name(), SepaText.NAME));
        payment.setCdtr(name);
        var account = factory.createCashAccountSEPA1(); account.setId(iban(factory, creditor.iban()));
        payment.setCdtrAcct(account);
        payment.setCdtrAgt(agent(factory, creditor.bic()));
        payment.setChrgBr(ChargeBearerTypeSEPACode.SLEV);
        var scheme = factory.createPartyIdentificationSEPA3();
        var party = factory.createPartySEPA2();
        var person = factory.createPersonIdentificationSEPA2();
        var identifier = factory.createRestrictedPersonIdentificationSEPA();
        identifier.setId(creditor.identifier());
        var schemeName = factory.createRestrictedPersonIdentificationSchemeNameSEPA(); schemeName.setPrtry(IdentificationSchemeNameSEPA.SEPA);
        identifier.setSchmeNm(schemeName);
        person.setOthr(identifier); party.setPrvtId(person); scheme.setId(party);
        payment.setCdtrSchmeId(scheme);
        for (var debit : block.debits()) { payment.getDrctDbtTxInf().add(transaction(factory, debit)); }
        return payment;
    }

    private static DirectDebitTransactionInformationSDD transaction(ObjectFactory factory, SepaDirectDebits.Debit debit) {
        var transaction = factory.createDirectDebitTransactionInformationSDD();
        var id = factory.createPaymentIdentificationSEPA(); id.setEndToEndId(debit.endToEndId());
        transaction.setPmtId(id);
        var amount = factory.createActiveOrHistoricCurrencyAndAmountSEPA();
        amount.setCcy(ActiveOrHistoricCurrencyCodeEUR.EUR); amount.setValue(SepaDirectDebits.euros(debit.amount()));
        transaction.setInstdAmt(amount);
        var mandate = factory.createMandateRelatedInformationSDD();
        mandate.setMndtId(debit.mandateRef()); mandate.setDtOfSgntr(date(debit.mandateSignedAt()));
        var direct = factory.createDirectDebitTransactionSDD(); direct.setMndtRltdInf(mandate);
        transaction.setDrctDbtTx(direct);
        transaction.setDbtrAgt(agent(factory, null));
        var debtor = factory.createPartyIdentificationSEPA2(); debtor.setNm(SepaText.of(debit.debtorName(), SepaText.NAME));
        transaction.setDbtr(debtor);
        var account = factory.createCashAccountSEPA2(); account.setId(iban(factory, debit.debtorIban()));
        transaction.setDbtrAcct(account);
        String information = SepaText.of(debit.remittanceInformation(), SepaText.REMITTANCE_INFORMATION);
        if (!information.isEmpty()) {
            var remittance = factory.createRemittanceInformationSEPA1Choice(); remittance.setUstrd(information);
            transaction.setRmtInf(remittance);
        }
        return transaction;
    }

    private static AccountIdentificationSEPA iban(ObjectFactory factory, String iban) {
        var id = factory.createAccountIdentificationSEPA(); id.setIBAN(SepaDirectDebits.compactIban(iban)); return id;
    }

    /** A creditor's or debtor's bank: its BIC, or `NOTPROVIDED` (the element is mandatory in pain.008.001.02). */
    private static BranchAndFinancialInstitutionIdentificationSEPA3 agent(ObjectFactory factory, String bic) {
        var agent = factory.createBranchAndFinancialInstitutionIdentificationSEPA3();
        var institution = factory.createFinancialInstitutionIdentificationSEPA3();
        if (bic != null && !bic.isBlank()) { institution.setBIC(bic.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT)); }
        else { var other = factory.createOthrIdentification(); other.setId(OthrIdentificationCode.NOTPROVIDED); institution.setOthr(other); }
        agent.setFinInstnId(institution);
        return agent;
    }

    private static XMLGregorianCalendar date(LocalDate date) { return Holder.DATES.newXMLGregorianCalendar(date.toString()); }
}
