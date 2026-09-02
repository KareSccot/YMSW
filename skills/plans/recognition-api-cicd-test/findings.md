# Findings: recognition-api CI/CD Pipeline Testing

## Finding 1: base-image-catalog.md Has Incorrect Image Names
- Catalog says: devops/jdk21_with_gradle_mvn
- team-cicd default says: cloud-infra/jdk21_with_gradle_mvn:v1.0.0
- NEITHER exists on TCR
- Only verified image: devops/jdk21.0.11_10-jdk-ubi9-minimal_mvn3.9.6:feat-ariba-144059 (ariba built and uses this)
- ACTION: Need TCR admin to list actual images in devops/ and cloud-infra/ namespaces, then update catalog

## Finding 2: TCR Permissions Are GitLab Group-Scoped
- ariba group (id 2310): DOCKER_AUTH_CONFIG has devops namespace pull access - pipeline works
- sf-btp-development group (id 1161): DOCKER_AUTH_CONFIG does NOT have devops namespace pull access - pipeline fails
- Same runner (10.247.24.86), same image - different outcome based on group credentials
- TCR login succeeds (REGISTRY_USER/PASSWORD works) but pull from devops namespace is denied
- SCA job log proves this: docker login succeeded, then docker pull devops/secscan failed with pull access denied

## Finding 3: Pipeline Structure Is Correct
- .gitlab-ci.yml include chain is valid: recognition-api -> team-cicd/backend-workflow.yml -> cicd-template/app-workflow.yml -> stages/docker-deploy.yml -> jobs/deploy/vm-deploy.yml
- deploy-container-uat inherited as when:on_success (auto-trigger after build-container)
- deploy-uat/prod (ArgoCD) correctly disabled (when:never)
- No hidden jobs incorrectly overridden by project
- IMAGE_TAG format: CI_COMMIT_REF_SLUG-CI_PIPELINE_ID (auto-exported by script)

## Finding 4: JDK17 Image Gap
- base-image-catalog only has jdk8/11/21 + jre8/11
- No jdk17 or jre17 in catalog
- Quick path: jdk21 can compile Java 17 code (backward compatible)
- Runtime fallback: eclipse-temurin:17-jre (public, may not be pullable from internal runner)
- Formal path: build jdk17/jre17 via base-image-builder (5-step process in catalog section B)

## Finding 5: Placeholder Validation Gap (FIXED)
- Skill previously had no enforcement step to verify placeholder values were filled
- recognition-api was a real-world example: skeleton generated, placeholders left unfilled, pipeline failed
- FIX IMPLEMENTED: Step 6 placeholder validation gate added to variables-output.md
- Scans generated .gitlab-ci.yml/Dockerfile/docker-compose.yml for unfilled placeholders
- Cross-references with variable checklist, categorizes by source (IT/platform/VM)
- Delivery gate: unfilled placeholders block done status
