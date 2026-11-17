# Buffered Flow checkpoint encoding

The historical worker rewrites a multi-megabyte checkpoint for each newly reviewed run. Replace streamed `json.dump` with `f.write(json.dumps(...))`, using the C encoder and one text write. The bytes, private temporary file, fsync, rename and failure cleanup remain unchanged. The in-memory encoded string adds about 5.5 MB for the measured checkpoint; the observed worker consumed approximately 82 MB within a 192 MB bound.

A read-only server benchmark encoded the same existing checkpoint three times per method into StringIO, comparing bytes outside timing. Streamed: 0.187/0.203/0.212 seconds; buffered: 0.075/0.104/0.077 seconds. This is an encoding measurement, not a claim about end-to-end import throughput. No production checkpoint or provider session changed in that benchmark.

Validation: `/private/tmp/canic-flow-worker-venv/bin/python -m unittest discover -s ops/flow_imports -v`, exit 0, 26 tests passed. New cases check legacy byte compatibility, private permissions, round-trip reading, and preservation/temporary cleanup after encoding or replacement failures. Existing replay, licence, retry, privacy and result-format tests remain passing. No Java, API contract or application artifact changed. Evidence: tests.log and benchmark.json.
