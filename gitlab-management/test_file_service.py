import os
import yaml
import base64
from dotenv import load_dotenv
from services.file_management.file_service import FileService

# Load environment variables
load_dotenv()

# Create file service instance
file_service = FileService()

# Test parameters
project_id = 1053
file_path = "app-values/dev/atlas-platform/dfx-gateway-service/dev-sh.yaml"
branch = "feat/dfx-sciwriter"

# First, get the current file content
print("Getting current file content...")
file_content = file_service.get_file(project_id, file_path, branch)
print(f"Current file content:\n{file_content['content']}")

# Parse YAML and update image tag
yaml_content = yaml.safe_load(file_content['content'])
old_version = yaml_content['image']['tag']
new_version = "dev-1.0.2"
yaml_content['image']['tag'] = new_version
updated_content = yaml.dump(yaml_content, default_flow_style=False)
print(f"\nUpdated content:\n{updated_content}")

# Try to update the file using file_service.update_file
try:
    print(f"\nTrying to update file using file_service.update_file...")
    result = file_service.update_file(
        project_id,
        file_path,
        updated_content,
        branch,
        "Update image version from test script"
    )
    print(f"Success! Updated file: {result['file_path']}")
    print(f"Old version: {old_version}, New version: {new_version}")
    print(f"New last commit ID: {result['last_commit_id']}")
except Exception as e:
    print(f"Error: {str(e)}")
    print(f"Error type: {type(e)}")
