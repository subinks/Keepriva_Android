# Phase 1 Visual Verification Retry Hardening

## Reason

Workflow run `35998170680` completed the build and all five parallel
instrumentation batches successfully. The visual-verification job failed after
vault creation because a single `uiautomator dump` returned:

```text
ERROR: null root node returned by UiTestAutomationBridge.
```

The failure artifact contained the expected vault-home screenshot, and the
failure diagnostic captured a valid hierarchy containing the expected search
field. A second workflow execution of the same commit passed. This establishes
that the failure was a transient UIAutomator synchronization race rather than
an application or test regression.

The successful duplicate run reported approximately 16.25 minutes overall,
but its job logs show only about 7 minutes 38 seconds of active execution. It
spent approximately 8 minutes 47 seconds queued behind the first run because
the workflow used `cancel-in-progress: false` for the shared branch concurrency
group.

## Changes

`scripts/ci/run-visual-verification.sh` now:

- retries each UI hierarchy capture up to 12 times;
- waits one second between attempts;
- deletes stale device-side and runner-side XML before every attempt;
- requires a non-empty XML hierarchy;
- waits for text identifying the intended setup, home, or unlock screen;
- records retry details in `uiautomator-dump-retries.log`;
- captures each blocking screenshot only after its screen is confirmed;
- retries the Python-driven setup hierarchy captures as well; and
- keeps biometric diagnostics optional and non-blocking.

The workflow concurrency policy now:

- uses a separate concurrency group per event type;
- cancels superseded push and pull-request runs; and
- does not cancel manual or scheduled serial-safety-net executions.

## Scope

This hardening changes the visual-verification CI helper, the concurrency
policy, and their Phase 1 payload copies. It does not modify application code,
resources, database logic, backup formats, instrumentation tests, test
allocation, or job topology.

## Expected verification

For a normal push to `ui_eh_ph01_parallel_ci`:

- the build job must pass;
- all five parallel instrumentation batches must pass;
- visual verification must pass;
- the serial safety net must remain skipped; and
- the final verification gate must pass.

The full serial safety net remains limited to manual workflow dispatch and the
weekly scheduled execution.
