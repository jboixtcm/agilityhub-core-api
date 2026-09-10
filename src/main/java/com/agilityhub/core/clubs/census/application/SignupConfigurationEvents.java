package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusEvent;
import com.agilityhub.core.shared.application.DomainEventHandler;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class SignupConfigurationEvents {
    @Bean DomainEventHandler<CensusEvent> signupPlanChanged(SignupService service) {return handler("PlanChanged",service);}
    @Bean DomainEventHandler<CensusEvent> signupPriceChanged(SignupService service) {return handler("PriceChanged",service);}
    @Bean DomainEventHandler<CensusEvent> signupParameterChanged(SignupService service) {return handler("ParameterChanged",service);}
    @Bean DomainEventHandler<CensusEvent> signupClubUpdated(SignupService service) {return handler("ClubUpdated",service);}
    private DomainEventHandler<CensusEvent> handler(String type,SignupService service) {
        return new DomainEventHandler<>() {public String eventType(){return type;}public Class<CensusEvent> eventClass(){return CensusEvent.class;}public void handle(String id,CensusEvent event){service.invalidateConfiguration(event.clubId());}};
    }
}
