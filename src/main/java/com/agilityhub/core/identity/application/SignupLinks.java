package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.platform.application.CensusClubSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SignupLinks {
    private final MagicLinkService links;private final CensusIdentityService accounts;private final CensusClubSettings clubs;
    public SignupLinks(MagicLinkService links,CensusIdentityService accounts,CensusClubSettings clubs) { this.links=links;this.accounts=accounts;this.clubs=clubs; }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void send(String id,String accountId,boolean welcome,java.util.Map<String,?> variables) {
        links.createAndSend(accounts.accessEmail(accountId),welcome?MagicLinkToken.Purpose.WELCOME:MagicLinkToken.Purpose.RECOGNITION,"clubs-app",welcome?null:"/gossos/nou",clubs.appHost(),null,null,id,variables);
    }
}
