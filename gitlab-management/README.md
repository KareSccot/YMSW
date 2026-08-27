# GitLab Management Service

A modular GitLab management service with multiple dedicated services and a common library.

## Project Structure

```
gitlab-management/
├── services/                  # Individual GitLab services
│   ├── project-management/    # Project-related functionality
│   ├── user-management/       # User-related functionality
│   └── ci-cd-management/      # CI/CD pipeline-related functionality
├── lib/                       # Common library for shared functionality
├── requirements.txt           # Python dependencies
├── .env.example               # Environment variables example
└── README.md                  # This file
```

## Services

### Project Management Service
- Create projects
- Get project details
- List projects

### User Management Service
- Create users
- Get user details
- List users

### CI/CD Management Service
- Run pipelines
- Get pipeline details
- List pipelines

## Common Library

The `lib` directory contains shared functionality used by all services:
- Base GitLab client with connection handling
- Environment variable management
- Common utilities

## Installation

1. Create a virtual environment:
   ```bash
   python3 -m venv venv
   source venv/bin/activate
   ```

2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```

3. Configure environment variables:
   ```bash
   cp .env.example .env
   # Edit .env file with your GitLab credentials
   ```

## Usage

Each service can be used independently:

```python
from services.project_management.project_service import ProjectService

# Initialize service
project_service = ProjectService()

# Create a new project
project = project_service.create_project("my-new-project")
print(f"Created project: {project.name}")
```

## Adding New Services

To add a new service:

1. Create a new directory under `services/`
2. Create service modules
3. Import and use the common library from `lib/`
4. Update `requirements.txt` if needed

## Requirements

See `requirements.txt` for the complete list of dependencies.
