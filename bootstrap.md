# Bootstrapping a new deployment repository

This guide walks through creating a new deployment repository from scratch. Replace `{env}` with your deployment code (e.g. `rw`, `et`, `ke`) and `{ENV_NAME}` with the human-readable name (e.g. "Rwanda").

## Step 1: Create the GitHub repository

Create a new private repo at `https://github.com/Zenysis/zen-dep-{env}` with an empty `master` branch.

Install the deployment GitHub App (ID `2731505`) on the new repo so that the main repo can trigger builds and clone it during web image builds.

## Step 2: Generate config and pipeline scaffolding

From the main Zenysis repo, run the scaffolding scripts:

```bash
./scripts/create_deployment_config.sh {env} "{ENV_NAME}"
./scripts/create_deployment_pipeline.sh {env} s3
```

These generate `config/{env}/` and `pipeline/{env}/` from the templates in `config/template/` and `pipeline/template/`.

## Step 3: Create the deployment repo files

Copy the generated files and create the required root files:

```
zen-dep-{env}/
├── .github/workflows/
│   └── aws-image-build-pipeline.yml
├── pipeline.env
├── web.env
├── roledef.py
├── deployment_credential.py
├── config/
│   └── (files from step 2)
└── pipeline/
    └── (files from step 2)
```

### `.github/workflows/aws-image-build-pipeline.yml`

Copy the template from this repo and update the `aws_role` to match the IAM role created in step 5:

```bash
mkdir -p .github/workflows
cp platform-repo/.github/workflow-templates/aws-image-build-pipeline.yml .github/workflows/
```

Edit the file and replace the `aws_role` value with your deployment's IAM role ARN.

### `pipeline.env`

```bash
PIPELINE_REPOSITORY="etl-pipeline-{env}"
ZEN_ENV="{env}"
PIPELINE_TARGET="{env_name}"          # e.g. "rwanda" — maps to pipeline/{env_name}/ in the repo
ROLEDEF_TARGET="{env}_internal"
```

### `web.env`

```bash
WEB_NAME="web-{env}"
ZEN_ENV="{env}"
ROLEDEF_TARGET="{env}_internal"
```

### `roledef.py`

Defines the infrastructure topology for this deployment. See the existing Rwanda deployment repo for a complete example. At minimum it must define:

```python
TOPOLOGY = {
    'druid_cluster': { ... },
    'hosts': { ... },
    'environments': { ... },
}
```

### `deployment_credential.py`

Maps deployment environment names to credential IDs:

```python
DEPLOYMENT_CREDENTIALS_MAP = {
    '{env}-internal-web-prod': <credential_id>,
    '{env}-internal-web-staging': <credential_id>,
}
```

### `config/`

Copy the output of `create_deployment_config.sh` from step 2. Key files to review and customize:

| File | What to configure |
|------|-------------------|
| `general.py` | `NATION_NAME`, `DEPLOYMENT_NAME`, `DEPLOYMENT_FULL_NAME` |
| `druid.py` | `DRUID_HOST`, `CLUSTERED_DRUID` |
| `ui.py` | Branding, locale, map center/zoom, feature flags |
| `pipeline_sources.py` | Data sources for this deployment |
| `aggregation.py` | Calendar settings, fiscal year start |

### `pipeline/`

Copy the output of `create_deployment_pipeline.sh` from step 2. Populate with data source connectors as needed under `bin/`, `config/`, `generate/`, `process/`, `validate/`, and `index/`.

## Step 4: Register in the main repo

Add a build environment file so the main repo knows about the deployment repo.

Create `docker/pipeline/build_env/{env}.env` in the main Zenysis repo:

```bash
DEPLOYMENT_REPO_URL="https://github.com/Zenysis/zen-dep-{env}"
```

This file serves two purposes:
1. The `notify-deployment-repos` job triggers the deployment repo's pipeline build after base images are rebuilt on `master`.
2. The `new_release` script creates matching release branches in the deployment repo.

## Step 5: Create AWS resources

### ECR repositories

Create two ECR repositories in `us-east-1`:

- `etl-pipeline-{env}` — pipeline image
- `web-{env}` — web image

### IAM role

Create an IAM role for GitHub Actions OIDC federation with ECR login, push, and pull permissions on the repositories above.

> **TODO:** ECR repository and IAM role creation should eventually be handled by Terraform.

Example trust policy (replace `zen-dep-{env}` with your repo name):

Trust policy (replace `zen-dep-{env}` with your repo name):

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {
                "Federated": "arn:aws:iam::251860034030:oidc-provider/token.actions.githubusercontent.com"
            },
            "Action": "sts:AssumeRoleWithWebIdentity",
            "Condition": {
                "StringEquals": {
                    "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
                },
                "StringLike": {
                    "token.actions.githubusercontent.com:sub": [
                        "repo:Zenysis/zen-dep-{env}:*"
                    ]
                }
            }
        }
    ]
}
```

Permission policy (replace `etl-pipeline-rw` with your ECR repository name):

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "ECRLogin",
            "Effect": "Allow",
            "Action": [
                "ecr:GetAuthorizationToken"
            ],
            "Resource": "*"
        },
        {
            "Sid": "ECRReadBaseImage",
            "Effect": "Allow",
            "Action": [
                "ecr:GetAuthorizationToken",
                "ecr:BatchGetImage",
                "ecr:GetDownloadUrlForLayer",
                "ecr:BatchImportUpstreamImage"
            ],
            "Resource": [
                "arn:aws:ecr:us-east-1:251860034030:repository/etl-pipeline-common",
                "arn:aws:ecr:us-east-1:251860034030:repository/etl-pipeline-{env}"
            ]
        },
        {
            "Sid": "ECRPushPipelineImage",
            "Effect": "Allow",
            "Action": [
                "ecr:BatchCheckLayerAvailability",
                "ecr:InitiateLayerUpload",
                "ecr:UploadLayerPart",
                "ecr:CompleteLayerUpload",
                "ecr:PutImage"
            ],
            "Resource": "arn:aws:ecr:us-east-1:251860034030:repository/etl-pipeline-{env}"
        },
        {
            "Sid": "ECRDeleteImage",
            "Effect": "Allow",
            "Action": [
                "ecr:BatchDeleteImage"
            ],
            "Resource": [
                "arn:aws:ecr:us-east-1:251860034030:repository/etl-pipeline-{env}",
                "arn:aws:ecr:us-east-1:251860034030:repository/web-{env}"
            ]
        }
    ]
}
```

## Step 6: Push and verify

1. Commit all files to `master` in the deployment repo and push.
2. The push triggers the `aws-image-build-pipeline.yml` workflow, which builds `etl-pipeline-{env}` on top of the latest `etl-pipeline-common:master`.
3. Verify the image appears in ECR with the correct build metadata labels:

```bash
aws ecr describe-images --repository-name etl-pipeline-{env} --region us-east-1
docker pull 251860034030.dkr.ecr.us-east-1.amazonaws.com/etl-pipeline-{env}:master
docker inspect etl-pipeline-{env}:master | jq '.[0].Config.Labels'
```

4. Merge the `docker/pipeline/build_env/{env}.env` change to `master` in the main repo. Future base image rebuilds will automatically notify the deployment repo.

## Step 7: Web image setup

For CodeBuild to build web images for this deployment, add the following environment variables to the CodeBuild project:

| Variable | Value |
|----------|-------|
| `DEPLOYMENT_REPO_URL` | `https://github.com/Zenysis/zen-dep-{env}` |

The `buildspec.yml` in the main repo handles cloning the deployment repo, sourcing `web.env`, and building the web image with deployment-specific config.

## Checklist

- [ ] GitHub repo `zen-dep-{env}` created and GitHub App installed
- [ ] `pipeline.env`, `web.env`, `roledef.py`, `deployment_credential.py` committed
- [ ] `config/` populated (from `create_deployment_config.sh` or manually)
- [ ] `pipeline/` populated (from `create_deployment_pipeline.sh` or manually)
- [ ] `.github/workflows/aws-image-build-pipeline.yml` committed with correct IAM role
- [ ] ECR repositories `etl-pipeline-{env}` and `web-{env}` created
- [ ] IAM role `GitHubAction-AssumeRoleWithAction-{env}` created with OIDC trust
- [ ] `docker/pipeline/build_env/{env}.env` added to main repo
- [ ] Pipeline image builds successfully from deployment repo
- [ ] Web image builds successfully from CodeBuild
