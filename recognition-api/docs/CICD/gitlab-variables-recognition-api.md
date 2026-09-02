# recognition-api UAT application variables

This file lists only the application secrets owned by the recognition-api project.
Runner, CICD template, registry login, image push/pull, SSH deployment, repository permissions,
release approval, and the related GitLab Variables are maintained by the GitLab/IT team and are
intentionally excluded.

## Variables supplied to the API container

| GitLab Variable | Required | Masked | Container Environment Variable | Purpose |
|---|---:|---:|---|---|
| `CUSTOM_UAT_MYSQL_USERNAME` | Yes | Yes | `UAT_MYSQL_USERNAME` | UAT MySQL username |
| `CUSTOM_UAT_MYSQL_PASSWORD` | Yes | Yes | `UAT_MYSQL_PASSWORD` | UAT MySQL password |
| `CUSTOM_UAT_JWT_SECRET` | Yes | Yes | `UAT_JWT_SECRET` | JWT signing secret |
| `CUSTOM_UAT_DATA_KEY` | Yes | Yes | `UAT_DATA_KEY` | Sensitive-data encryption key |

The repository does not contain plaintext defaults for these four values. If a value is missing or
unresolved, the API stops during startup and identifies the missing application property.

## Non-sensitive CI values still awaiting confirmation

The following placeholders remain in `.gitlab-ci.yml` and must be completed with values confirmed by
the GitLab/IT team. They are not part of the application Variables workbook.

| CI value | Current state |
|---|---|
| `DOCKER_REGISTRY` | `<DOCKER_REGISTRY>` |
| `SERVICE_REPOSITORY` | `<SERVICE_REPOSITORY>` |
| `API_BUILD_IMAGE` | Build image path and tag pending |
| `API_RUNTIME_BASE_IMAGE` | Runtime image path and tag pending |
