# 0007 — Independent case and decision reviewers

Status: Proposed

Date: 2026-10-12

Deciders: maintainers

## Context

A user may have both operator and reviewer roles. Role membership alone would let an operator approve their own case or
ordinary generated decision, while the previous independent-approval check covered manual exceptions only.

## Decision

Persist case initiation from the authenticated identity and decision initiation from the durable execution submitter.
Reject review claim, approval, and reassignment to the same case or decision initiator, including supervisors.
Check approval independently of claim so legacy assignments cannot bypass the rule. Bind every action to the immutable
current decision version and evidence snapshot. Uploaded material also requires a different reviewer.

## Consequences

Small teams need at least two authorized identities. Reassignment targets come from the server-managed directory and
must match case scope. Immutable JDBC fixtures test both positive independent review and rejected initiation identities.
A model response or client request cannot choose the persisted decision author. Legacy decisions with missing or blank
initiators require a new analysis; assigning a reviewer cannot establish the missing authorship evidence.

## Rejected alternatives

Reviewer-role membership alone allows an operator with both roles to self-approve. Checking only at claim would permit a legacy or forged assignment to bypass approval. A supervisor override would remove the independence guarantee without any recorded independent decision.

## Verification

Run CaseServiceTest with immutable JDBC decisions. Verify independent approval, same-case initiator claim rejection, same-decision initiator claim and approval rejection after a legacy assignment, rejection of legacy decisions without verified initiators, and content verification by a different identity. Run strict API request validation and server-owned author assertions.
