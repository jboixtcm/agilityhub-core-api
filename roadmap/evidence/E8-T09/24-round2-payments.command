./mvnw -q -Dtest=StripeCallsTest,AuditWriterTest -Dit.test=CardPaymentsIT,PaymentCurlIT test failsafe:integration-test failsafe:verify
