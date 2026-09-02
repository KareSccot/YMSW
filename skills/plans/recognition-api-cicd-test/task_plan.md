# Task Plan: recognition-api CI/CD Pipeline Testing

## Goal
Diagnose why recognition-api pipeline fails while ariba runs successfully, identify root cause, and document findings for future project onboarding.

## Phases

### Phase 1: Analysis and Diagnosis [complete]
- Clone recognition-api, analyze .gitlab-ci.yml, Dockerfile.cicd, docker-compose.yml
- Identify 4 unfilled placeholders: DOCKER_REGISTRY, SERVICE_REPOSITORY, API_BUILD_IMAGE, API_RUNTIME_BASE_IMAGE
- Map complete include chain: recognition-api -> team-cicd -> cicd-template -> docker-deploy -> vm-deploy
- Confirm deploy-container-uat inherited correctly (when:on_success)

### Phase 2: Test Branch Creation [complete]
- Open feat/cicd-fix-test branch (later deleted per owner)
- Add variable documentation, VM runbook, .gitlab-ci.yml comments
- Delete stale root Dockerfile

### Phase 3: Pipeline Testing [complete]
- Attempt 1: devops/jdk21_with_gradle_mvn:latest - FAILED (image does not exist)
- Attempt 2: cloud-infra/jdk21_with_gradle_mvn:v1.0.0 - FAILED (image does not exist)
- Attempt 3: devops/jdk21.0.11_10-jdk-ubi9-minimal_mvn3.9.6:feat-ariba-144059 - FAILED (pull access denied)
- Root cause identified: TCR account permission issue, not image name issue

### Phase 4: Documentation [in_progress]
- Record findings in planning files
- Update base-image-catalog.md (ACTION ITEM - needs TCR admin verification)
- Document permission requirements for future project onboarding

## Key Decisions
- Owner decided to stop testing and delete branch (IT permission issue, not code-fixable)
- base-image-catalog.md image names do not match actual TCR contents - needs correction
