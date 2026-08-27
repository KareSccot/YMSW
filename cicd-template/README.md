# cicd-template
This repo targets to resolve CICD implementations 
- based on Gitlab CI
- encourage DRY
- allow certain flexibility 

## Folder structure
```bash
.
├── jobs
│   ├── approval
│   │   └── release-approval.yml
│   ├── build
│   │   ├── docker.yml
│   │   ├── gradle.yml
│   │   ├── mvn.yml
│   │   └── pnpm.yml
│   ├── deploy
│   │   ├── argo-rolling.yml
│   │   └── bucket.yml
│   ├── iac
│   │   ├── apply.yml
│   │   └── plan.yml
│   ├── quality
│   │   └── sonar-scan.yml
│   ├── release
│   │   ├── create-release.yml
│   │   └── finalize-release.yml
│   └── security
│       ├── dast.yml
│       ├── sast.yml
│       └── sca.yml
├── README.md
├── rules
│   ├── branch-conditions.yml
│   ├── dev-fix-rules.yml
│   └── release-rules.yml
├── stages
│   ├── approval.yml
│   ├── argo-deploy.yml
│   ├── container-build.yml
│   ├── gradle-build.yml
│   ├── mvn-build.yml
│   ├── pnpm-build.yml
│   ├── release.yml
│   ├── security-scan.yml
│   └── sonar-scan.yml
└── workflows
    ├── app-workflow.yml
    └── iac-workflow.yml
```

## Architecture
- Workflows combine stages 
- Stages have jobs implementation combined by job scripts, rules and other settings
- Rules have different conditions and combination, which decide when jobs will appear, etc, in fix branch or in tag
- Jobs have real scripts/implementations to decide what usually is done in a type of job
