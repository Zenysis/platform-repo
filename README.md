# Platform Repo

Shared GitHub Actions for building Zenysis Docker images across the main repository and deployment repositories.

## What lives here

This repository contains reusable GitHub Actions that are referenced by both the [main Zenysis repository](https://github.com/Zenysis/zenysis) and individual deployment repositories (e.g. `zen-dep-rw`). Currently:

- **`.github/actions/build-pipeline-final`** — Builds the final pipeline Docker image for a given integration. It layers deployment-specific code (pipeline scripts, config, role definitions, credentials) on top of the shared `etl-pipeline-common` base image.

## Deployment repositories

A deployment repository contains all the code and configuration specific to a single deployment (country/region). It is kept separate from the main Zenysis codebase so that client contributors can work on deployment-specific changes without access to the full platform.

To create a new deployment repository, see [bootstrap.md](bootstrap.md).

### What lives in a deployment repo

```
zen-dep-{env}/
├── .github/workflows/
│   └── aws-image-build-pipeline.yml    # Triggers pipeline image builds
├── pipeline.env                        # Pipeline build config (ZEN_ENV, image name, targets)
├── web.env                             # Web build config (ZEN_ENV, image name, roledef target)
├── roledef.py                          # Machine topology and service definitions
├── deployment_credential.py            # Credential mappings
├── dataprep_token                      # Dataprep access token (blank if not used)
├── config/                             # Deployment-specific platform configuration
│   ├── aggregation.py                  # (use `scripts/create_deployment_config.sh` from
│   ├── druid.py                        #  the main repo to generate)
│   ├── general.py
│   ├── pipeline_sources.py
│   ├── ui.py
│   └── ...
└── pipeline/                           # Integration-specific ETL pipeline
    ├── bin/                            # Executable scripts
    ├── config/                         # Data source configurations
    ├── generate/                       # Data extraction scripts
    ├── process/                        # Processing logic
    ├── validate/                       # Validation rules
    ├── index/                          # Druid indexing configuration
    └── static_data/                    # Static reference data
```

### Key environment files

- **`pipeline.env`** — Defines `PIPELINE_REPOSITORY`, `ZEN_ENV`, `PIPELINE_TARGET`, `ROLEDEF_TARGET`. Used by the `build-pipeline-final` action.
- **`web.env`** — Defines `WEB_NAME`, `ZEN_ENV`, `ROLEDEF_TARGET`. Used by the CodeBuild web image build.

## Branch model

Both the main Zenysis repo and deployment repos follow the same branching convention:

| Branch | Purpose |
|--------|---------|
| `master` | Active development. All new work lands here. |
| `zen-release-YYYYMMDD` | Release branches. Created from `master` when cutting a release. |

Release branches are created in lockstep across all repos using `prod/scripts/new_release` in the main repo, which creates the `zen-release-*` branch in the main repo and in every deployment repo that has a `DEPLOYMENT_REPO_URL` defined in `docker/pipeline/build_env/*.env`.

## How builds are triggered

### Pipeline images (GitHub Actions)

The Docker image build chain for pipeline images:

```
etl-pipeline-base  →  etl-pipeline-common  →  etl-pipeline-{env}
   (Dockerfile.base)     (Dockerfile.common)     (Dockerfile.final)
   [main repo]           [main repo]              [platform-repo action]
```

**From the main repo:**

1. A push to `master` triggers `.github/workflows/aws-image-build-pipeline.yml`.
2. The workflow builds `etl-pipeline-base` and `etl-pipeline-common` (shared across all deployments).
3. It builds final images for integrations defined in `docker/pipeline/build_env/*.env` that don't have a `DEPLOYMENT_REPO_URL`.
4. It then notifies each deployment repo (those with a `DEPLOYMENT_REPO_URL`) by triggering their `aws-image-build-pipeline.yml` workflow on `main`.

**From a deployment repo:**

1. A push to `master` (or a trigger from the main repo) runs the deployment repo's own `aws-image-build-pipeline.yml`.
2. That workflow calls the `build-pipeline-final` action from this platform-repo.
3. The action detects `pipeline.env`, sources it, and builds the final `etl-pipeline-{env}` image on top of the latest `etl-pipeline-common`.

When a deployment workflow sets `main_repo_branch`, the shared action sanitizes that branch name into the Docker tag format used by the main repo before resolving `etl-pipeline-common:<tag>`. This keeps slash-delimited Git branch names compatible with image references.

### Web images (AWS CodeBuild)

Web images are built via `buildspec.yml` in the main repo. When `DEPLOYMENT_REPO_URL` is set in the CodeBuild environment:

1. The deployment repo is cloned and `web.env` is sourced.
2. Config and role definition files are copied from the deployment repo into the main repo's build context.
3. The web image is built with deployment-specific configuration baked in.

The build chain:

```
web-client + web-server  →  web-common  →  web-{env}
  (Dockerfile_web-client)    (Dockerfile_web-common)  (Dockerfile_web)
  (Dockerfile_web-server)
```

## Build metadata

All built images carry traceability labels, inspectable via `docker inspect`:

| Label | Set in | Description |
|-------|--------|-------------|
| `org.zenysis.platform.commit-sha` | Common image | Main repo commit SHA |
| `org.zenysis.platform.branch` | Common image | Main repo branch name |
| `org.zenysis.build.timestamp` | Common image | Build time (ISO 8601) |
| `org.zenysis.deployment.repo-url` | Final image | Deployment repo URL (if applicable) |
| `org.zenysis.deployment.branch`   | Final image | Deployment repo branch name         |
| `org.zenysis.deployment.commit-sha` | Final image | Deployment repo commit SHA (if applicable) |

Platform labels are set on the common images and inherited by final images via `FROM`. Deployment labels are set only on the final images.
