from pathlib import Path
import subprocess,shlex,sys
root=Path(__file__).resolve().parent
name="12-report-focused-integration"
command=["./mvnw","-q","test-compile","failsafe:integration-test","-Dit.test=MigrationReportsIT,PlayoffMigrationIT,SecurityInventoryIT","failsafe:verify"]
(root/(name+".command")).write_text(shlex.join(command)+"\nExecuted under host heavy.sh lock.\n")
with (root/(name+".log")).open("w") as log:
    result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
(root/(name+".exit")).write_text(str(result.returncode)+"\n")
print(name,"exit",result.returncode,flush=True)
print("\n".join((root/(name+".log")).read_text().splitlines()[-40:]),flush=True)
sys.exit(result.returncode)
