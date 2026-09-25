# Phase 2 CI Test-Count Guard Fix

GitHub Actions run `36099440213` compiled and built the application successfully. All 32 tests in the `auth-lifecycle-smoke` instrumentation batch passed, but the Phase 1 helper still required the previous count of 26 and therefore returned an error after the successful test run.

Phase 2 added six lifecycle tests, so the correct blocking counts are:

| Guard | Phase 1 | Phase 2 |
|---|---:|---:|
| `auth-lifecycle-smoke` | 26 | 32 |
| Full blocking matrix | 88 | 94 |
| `serial-safety-net` | 88 | 94 |
| Complete instrumentation inventory, including diagnostic | 89 | 95 |

No application source, resource, database, backup, manifest, or test implementation needs to change. The correction changes only `scripts/ci/run-instrumentation-batch.sh`.
