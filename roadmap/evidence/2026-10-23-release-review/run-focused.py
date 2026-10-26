from pathlib import Path
import subprocess, shlex, sys
root=Path(__file__).resolve().parent
commands=[
    ("10-report-test-compile", ["./mvnw", "-q", "-DskipTests", "test-compile"]),
    ("11-report-focused-units", ["./mvnw", "-q", "-Dtest=MigrationReportsTest,S3MigrationReportStoreTest,InputManifestTest,MigrationReportCleanupTest,MigrationReportCommandTest,MigrationWelcomeCommandTest,MigrationWelcomeDeliveryTest,CommandSchedulingGuardTest,E9ResponseContractTest,PlayoffAnonymizerPathsTest", "test"]),
]
for name,command in commands:
    (root/(name+".command")).write_text(shlex.join(command)+"\nRun while host heavy.sh lock is held by run-focused.py.\n")
    with (root/(name+".log")).open("w") as output:
        result=subprocess.run(command,stdout=output,stderr=subprocess.STDOUT)
    (root/(name+".exit")).write_text(str(result.returncode)+"\n")
    print(name,"exit",result.returncode,flush=True)
    if result.returncode:
        print("\n".join((root/(name+".log")).read_text().splitlines()[-35:]),flush=True)
        sys.exit(result.returncode)
