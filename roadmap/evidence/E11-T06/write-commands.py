#!/usr/bin/env python3
"""E11-T06: `.command` files for this session's non-PIT logs (the exact command line typed; output redirected to the log).

Existing `.command` files are not overwritten. PIT runs have theirs from `pit/commands.py`.
"""
from pathlib import Path

HERE = Path(__file__).resolve().parent
HEAVY = '/Users/jordib/Dropbox/Documents/SOFTWARE_CANIC/05-desenvolupament/roadmap-kit/mac/heavy.sh'
E = 'roadmap/evidence/E11-T06/'
COMMANDS = {
    '25-focused-unit': "./mvnw -q -Dtest=SentryCaptureTest,HealthControllerTest,LogPrivacyTest,AnonymousRateLimitsTest -Dsurefire.failIfNoSpecifiedTests=false test",
    '26-counterfactual-attempt1': "python3 roadmap/evidence/E11-T06/26-counterfactual.py",
    '27-counterfactual': "python3 roadmap/evidence/E11-T06/26-counterfactual.py 27-counterfactual",
    '33-survivor-tests-batch1-2': "./mvnw -q '-Dtest=*SurvivorsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '35-survivor-tests-batch3': "./mvnw -q '-Dtest=*SurvivorsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '37-survivor-tests-batch4': "./mvnw -q '-Dtest=*SurvivorsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '39-survivor-tests-batch5': "./mvnw -q '-Dtest=*SurvivorsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '41-survivor-tests-batch6': "./mvnw -q '-Dtest=*SurvivorsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '43-review-fixes-tests': f"{HEAVY} ./mvnw -q '-Dtest=SentryCaptureTest,CheckoutServiceSurvivorsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '44-review-fixes-and-survivors': "./mvnw -q '-Dtest=SentryCaptureTest,*SurvivorsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '48-survivor-tests-rerun2': "./mvnw -q '-Dtest=*SurvivorsTest,*Survivors2Test' -Dsurefire.failIfNoSpecifiedTests=false test",
    '54-billing-transactions-tests': "./mvnw -q '-Dtest=BillingTransactions*' -Dsurefire.failIfNoSpecifiedTests=false test",
    '56-counterfactual': "python3 roadmap/evidence/E11-T06/26-counterfactual.py 56-counterfactual",
    '57-clean-verify': f"{HEAVY} ./mvnw -q clean verify",
    '58-clean-verify-all-results': f"{HEAVY} ./mvnw -q clean verify -Dmaven.test.failure.ignore=true",
    '59-test-summary': "python3 roadmap/evidence/E11-T06/test-summary.py SentryCaptureTest LogPrivacyTest RateLimitIT AnonymousRateLimitsTest SecurityInventoryIT HealthControllerTest E8ContractIT BillingTransactionsTest ArchitectureTest EventCatalogContractTest",
    '60-openapi-snapshot': "bin/openapi-snapshot --from-build",
    '61-image-build': f"{HEAVY} docker build -t ghcr.io/jboixtcm/agilityhub-core-api:e11-t06 .",
    '62-image-scan': "bin/security-scan image ghcr.io/jboixtcm/agilityhub-core-api:e11-t06",
    '63-deploy-smoke': f"{HEAVY} bin/deploy-smoke --image-tag e11-t06 --evidence-prefix roadmap/evidence/E11-T06/63-proof",
    '64-security-inventory': "bin/security-inventory",
    '66-deploy-tests': "python3 -m unittest -v deploy.test_round2",
    '67-mutation-gate-test': "python3 bin/mutation-gate-test.py",
    '68-diff-check': "git diff --check",
    '71-pit-suspect-killers': "python3 roadmap/evidence/E11-T06/pit/killers.py <test method> roadmap/evidence/E11-T06/pit/final-batch{1..7}-mutations.xml (once per suspect method, appended)",
    '73-survivor-tests-bookings8': "./mvnw -q '-Dtest=com.agilityhub.core.clubs.bookings.**.*SurvivorsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '75-survivor-tests-all': "./mvnw -q '-Dtest=*Survivors*Test,SentryCaptureTest,BillingTransactionsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '76-survivor-tests-all': "./mvnw -q '-Dtest=*Survivors*Test,SentryCaptureTest,BillingTransactionsTest' -Dsurefire.failIfNoSpecifiedTests=false test",
    '77-export-root-its': "./mvnw -q '-Dtest=RemittancesIT,BillingFollowupsIT' -Dsurefire.failIfNoSpecifiedTests=false test",
    '79-survivor-tests-all': "./mvnw -q '-Dtest=*Survivors*Test' -Dsurefire.failIfNoSpecifiedTests=false test",
    '80-survivor-tests-all': "./mvnw -q '-Dtest=*Survivors*Test' -Dsurefire.failIfNoSpecifiedTests=false test",
    '82-survivor-tests-all': "./mvnw -q '-Dtest=*Survivors*Test,LearnCsvTest' -Dsurefire.failIfNoSpecifiedTests=false test",
}


def main():
    for name, line in COMMANDS.items():
        if not (HERE / f'{name}.log').exists():
            print('no log for', name)
            continue
        target = HERE / f'{name}.command'
        if target.exists():
            print('kept', target.name)
            continue
        target.write_text(f'{line} > {E}{name}.log 2>&1\n')
        print('wrote', target.name)


if __name__ == '__main__':
    main()
