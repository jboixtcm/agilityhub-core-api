from pathlib import Path
import subprocess,shlex,sys
root=Path(__file__).resolve().parent
commands=[
("13-report-units-retry",["./mvnw","-q","-Dtest=MigrationReportsTest,S3MigrationReportStoreTest,InputManifestTest,MigrationReportCleanupTest,MigrationReportCommandTest","test"]),
("14-report-integration-retry",["./mvnw","-q","test-compile","failsafe:integration-test","-Dit.test=MigrationReportsIT","failsafe:verify"])]
for name,command in commands:
    (root/(name+".command")).write_text(shlex.join(command)+"\nExecuted under host heavy.sh lock.\n")
    with (root/(name+".log")).open("w") as log: result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    (root/(name+".exit")).write_text(str(result.returncode)+"\n")
    print(name,"exit",result.returncode,flush=True)
    if result.returncode:
        print("\n".join((root/(name+".log")).read_text().splitlines()[-40:]),flush=True)
        sys.exit(result.returncode)
