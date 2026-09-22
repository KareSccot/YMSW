import gitlab
import os
import yaml
import base64
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

# Initialize GitLab client
gl = gitlab.Gitlab(
    os.getenv('GITLAB_URL'),
    private_token=os.getenv('GITLAB_TOKEN'),
    ssl_verify=False
)

# Get the project
project = gl.projects.get(1053)

# Get the file manager
files_manager = project.files

# Test updating an existing file using create() method with last_commit_id
file_path = "app-values/dev/atlas-platform/dfx-gateway-service/dev-sh.yaml"
branch = "feat/dfx-sciwriter"

# Get the current file
current_file = files_manager.get(file_path=file_path, ref=branch)

# Decode and update content
content = base64.b64decode(current_file.content).decode('utf-8')
yaml_content = yaml.safe_load(content)
old_version = yaml_content['image']['tag']
new_version = "dev-1.0.1"
yaml_content['image']['tag'] = new_version
updated_content = yaml.dump(yaml_content, default_flow_style=False)

# Try to update using create() method with last_commit_id
try:
    print(f"Trying to update {file_path}...")
    print(f"Old version: {old_version}, New version: {new_version}")
    
    # This approach works for some GitLab API versions
    updated_file = files_manager.create({
        'file_path': file_path,
        'branch': branch,
        'content': updated_content,
        'commit_message': "Update image version",
        'last_commit_id': current_file.last_commit_id
    })
    
    print(f"Success! Updated file: {updated_file.file_path}")
    print(f"New last commit ID: {updated_file.last_commit_id}")
    
    # Verify the update
    print("\nVerifying the update...")
    verified_file = files_manager.get(file_path=file_path, ref=branch)
    verified_content = base64.b64decode(verified_file.content).decode('utf-8')
    verified_yaml = yaml.safe_load(verified_content)
    print(f"Verified image tag: {verified_yaml['image']['tag']}")
    
except Exception as e:
    print(f"Error: {str(e)}")
    print(f"Error type: {type(e)}")
    import traceback
    traceback.print_exc()
