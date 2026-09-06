package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SendGridWebhookReceiptRepository extends GlobalRepository<SendGridWebhookReceipt> {
    public SendGridWebhookReceiptRepository(MongoTemplate mongo) { super(mongo, SendGridWebhookReceipt.class); }
    public void insert(SendGridWebhookReceipt receipt) { mongo.insert(receipt); }
}
