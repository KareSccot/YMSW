import os
from dotenv import load_dotenv
from services.argocd_deployment.version_service import VersionService

# Load environment variables
load_dotenv()

# Create version service instance
version_service = VersionService()

# Test parameters
project_id = 1053
file_path = "app-values/dev/atlas-platform/dfx-gateway-service/dev-sh.yaml"
branch = "feat/dfx-sciwriter"
new_version = "dev-1.0.3"

# Test the update_image_version method
print("Testing VersionService.update_image_version...")
try:
    result = version_service.update_image_version(
        project_id,
        file_path,
        new_version,
        branch,
        "Update image version from test script"
    )
    print(f"Success! Image version updated:")
    print(f"  File path: {result['file_path']}")
    print(f"  Old version: {result['old_version']}")
    print(f"  New version: {result['new_version']}")
    print(f"  Last commit ID: {result['last_commit_id']}")
    print(f"  Message: {result['message']}")
except Exception as e:
    print(f"Error: {str(e)}")
    print(f"Error type: {type(e)}")
