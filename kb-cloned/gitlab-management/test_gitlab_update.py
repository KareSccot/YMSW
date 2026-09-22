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

# Get the current file
file_path = "app-values/dev/atlas-platform/dfx-gateway-service/dev-sh.yaml"
branch = "master"
current_file = project.files.get(file_path=file_path, ref=branch)

# Print current file details
print(f"Current file: {current_file.file_path}")
print(f"Current last commit ID: {current_file.last_commit_id}")

# Decode the file content
content = base64.b64decode(current_file.content).decode('utf-8')
print(f"Current content:\n{content}")

# Parse YAML
yaml_content = yaml.safe_load(content)

# Update the image tag
if 'image' in yaml_content and 'tag' in yaml_content['image']:
    old_version = yaml_content['image']['tag']
    new_version = "dev-1.0.1"
    yaml_content['image']['tag'] = new_version
    
    # Convert back to YAML
    updated_content = yaml.dump(yaml_content, default_flow_style=False)
    print(f"Updated content:\n{updated_content}")
    
    # Try to update the file using different methods
    print("\nTrying to update file...")
    
    # Method 1: Using project.files.update with keyword arguments
    try:
        print("\nMethod 1: Using project.files.update with keyword arguments")
        updated_file = project.files.update(
            file_path=file_path,
            branch=branch,
            content=updated_content,
            commit_message="Update image version",
            last_commit_id=current_file.last_commit_id
        )
        print(f"Success! Updated file: {updated_file.file_path}")
        print(f"New last commit ID: {updated_file.last_commit_id}")
    except Exception as e:
        print(f"Error with Method 1: {str(e)}")
        print(f"Error type: {type(e)}")
    
    # Method 2: Using file.save()
    try:
        print("\nMethod 2: Using file.save()")
        # Reset to original content first
        current_file = project.files.get(file_path=file_path, ref=branch)
        current_file.content = updated_content
        current_file.branch = branch
        current_file.commit_message = "Update image version"
        current_file.save()
        print(f"Success! Updated file: {current_file.file_path}")
        print(f"New last commit ID: {current_file.last_commit_id}")
    except Exception as e:
        print(f"Error with Method 2: {str(e)}")
        print(f"Error type: {type(e)}")
    
    # Method 3: Using project.files.create (force update)
    try:
        print("\nMethod 3: Using project.files.create (force update)")
        # Get the latest last_commit_id
        current_file = project.files.get(file_path=file_path, ref=branch)
        updated_file = project.files.create(
            {
                'file_path': file_path,
                'branch': branch,
                'content': updated_content,
                'commit_message': "Update image version",
                'last_commit_id': current_file.last_commit_id
            }
        )
        print(f"Success! Updated file: {updated_file.file_path}")
        print(f"New last commit ID: {updated_file.last_commit_id}")
    except Exception as e:
        print(f"Error with Method 3: {str(e)}")
        print(f"Error type: {type(e)}")
else:
    print("image.tag field not found in the file")
