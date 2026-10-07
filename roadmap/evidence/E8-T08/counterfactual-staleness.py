from pathlib import Path
import subprocess,sys
path=Path('src/main/java/com/agilityhub/core/payments/application/BillingSimulationService.java')
original=path.read_text()
a=original.index('    static List<String> chargeIds(')
b=original.index('    static List<String> waitingInvoiceIds(',a)
try:
    path.write_text(original[:a]+'    static List<String> chargeIds(InvoicingService.MonthPlan plan) { return plan.charges().keySet().stream().sorted().toList(); }\n'+original[b:])
    cmd=['./mvnw','-q','-DskipTests','test-compile','failsafe:integration-test','failsafe:verify','-DskipTests=false','-Dit.test=BillingFollowupsIT#R_12_07_newChargesOutsideTheBilledDraftsDoNotMakeASimulationStale']
    print('$ '+' '.join(cmd),flush=True)
    result=subprocess.run(cmd)
    print('counterfactual Maven exit',result.returncode,flush=True)
finally:
    path.write_text(original)
print('Original implementation restored.',flush=True)
sys.exit(0 if result.returncode==1 else 1)
