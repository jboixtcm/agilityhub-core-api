package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusEvent;
import com.agilityhub.core.shared.application.DomainEventHandler;
import org.springframework.context.annotation.*;

/**
 * The `GET /signup` configuration cache follows its sources: plans, prices, parameters, the club's own data and modules.
 * E3-T09 step 5: each eviction runs right after the writer's commit on this instance, so `GET /signup` just after
 * `PUT /parameters/signup.enabled` answers the new value; the outbox delivery stays the backstop. `club:apply` publishes
 * `ClubConfigChanged`, whose catalog type is `ClubUpdated`, so a provider switched in a club definition arrives here too.
 */
@Configuration(proxyBeanMethods=false)
public class SignupConfigurationEvents {
    @Bean DomainEventHandler<CensusEvent> signupPlanChanged(SignupService service) {return handler("PlanChanged",service);}
    @Bean DomainEventHandler<CensusEvent> signupPriceChanged(SignupService service) {return handler("PriceChanged",service);}
    @Bean DomainEventHandler<CensusEvent> signupParameterChanged(SignupService service) {return handler("ParameterChanged",service);}
    @Bean DomainEventHandler<CensusEvent> signupClubUpdated(SignupService service) {return handler("ClubUpdated",service);}
    @Bean DomainEventHandler<CensusEvent> signupClubModulesChanged(SignupService service) {return handler("ClubModulesChanged",service);}
    private DomainEventHandler<CensusEvent> handler(String type,SignupService service) {
        return new DomainEventHandler<>() {public String eventType(){return type;}public Class<CensusEvent> eventClass(){return CensusEvent.class;}public void handle(String id,CensusEvent event){service.invalidateConfiguration(event.clubId());}
            @Override public boolean evictsAfterCommit(){return true;}};
    }
}
